package com.company.vpc.service;

import com.company.vpc.common.PageResult;
import com.company.vpc.domain.JumphostMigrationHistory;
import com.company.vpc.domain.NetworkSnapshot;
import com.company.vpc.domain.PeeringHistory;
import com.company.vpc.domain.enums.CspType;
import com.company.vpc.domain.enums.MigrationStatus;
import com.company.vpc.domain.enums.PeeringStatus;
import com.company.vpc.domain.enums.SnapshotDataType;
import com.company.vpc.util.CidrUtils;
import com.company.vpc.dto.request.PeeringCreateRequest;
import com.company.vpc.dto.request.PeeringMigrationRequest;
import com.company.vpc.dto.response.MigrationHistoryResponse;
import com.company.vpc.dto.response.NetworkSnapshotResponse;
import com.company.vpc.dto.response.PeeringHistoryResponse;
import com.company.vpc.dto.response.PeeringMigrationResponse;
import com.company.vpc.dto.response.PeeringMigrationStartResponse;
import com.company.vpc.dto.response.PeeringResultDto;
import com.company.vpc.exception.BusinessException;
import com.company.vpc.exception.ErrorCode;
import com.company.vpc.mapper.JumphostMigrationHistoryMapper;
import com.company.vpc.mapper.NetworkSnapshotMapper;
import com.company.vpc.mapper.PeeringHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VPC Peering 생명주기 관리 서비스 — 오케스트레이션 계층.
 *
 * <p>AWS 연산은 {@link CspPeeringService} 전략 구현체에 위임하고,
 * 이 클래스는 DB 이력 관리·CIDR 사전 검증·스냅샷 저장 등 공통 흐름을 담당한다.
 *
 * <p>트랜잭션 전략:
 * <ul>
 *   <li>클래스 레벨: {@code readOnly = true} (조회 메서드 기본)</li>
 *   <li>쓰기 메서드: {@code @Transactional}로 개별 선언</li>
 * </ul>
 *
 * <p>주의: AWS API 호출은 DB 트랜잭션과 무관하다.
 * AWS 오류 시 DB 롤백은 되지만 이미 생성된 AWS 리소스는 보상 트랜잭션으로 별도 정리해야 한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PeeringManagementService {

    private final PeeringHistoryMapper historyMapper;
    private final NetworkSnapshotMapper snapshotMapper;
    private final JumphostMigrationHistoryMapper migrationMapper;

    /**
     * 모든 CSP 구현체 목록 — Spring이 {@link CspPeeringService}의 모든 Bean을 자동 주입한다.
     * {@link #resolveService}에서 getCspType()으로 적합한 구현체를 선택한다.
     */
    private final List<CspPeeringService> cspServices;

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VPC Peering 생성.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>CIDR 겹침 사전 검증 → 겹치면 즉시 예외</li>
     *   <li>PENDING 상태로 DB에 이력 INSERT</li>
     *   <li>AWS API 5단계 실행 (CspPeeringService 위임)</li>
     *   <li>성공 시 ACTIVE로 UPDATE, 실패 시 FAILED로 UPDATE 후 예외 재throw</li>
     * </ol>
     *
     * @param request 생성 요청 DTO
     * @return 생성된 Peering 이력 응답 DTO
     * @throws BusinessException CIDR 겹침, AWS API 오류 시
     */
    @Transactional
    public PeeringHistoryResponse createPeering(PeeringCreateRequest request) {
        // CIDR 겹침 검증 — AWS API 호출 전에 차단해 불필요한 AWS 비용 방지
        if (CidrUtils.isOverlapping(request.getRequesterCidr(), request.getAccepterCidr())) {
            throw new BusinessException(ErrorCode.CIDR_OVERLAP,
                    String.format("%s ↔ %s", request.getRequesterCidr(), request.getAccepterCidr()));
        }

        // PENDING 상태로 이력 먼저 저장 — AWS 실패 시에도 시도 이력이 남도록
        PeeringHistory history = PeeringHistory.create(
                request.getPeeringName(), CspType.AWS,
                request.getRequesterVpcId(), request.getRequesterCidr(),
                request.getRequesterAccountId(), request.getRequesterRouteTableId(),
                request.getAccepterVpcId(), request.getAccepterCidr(),
                request.getAccepterAccountId(), request.getAccepterRouteTableId(),
                request.getAccepterSecurityGroupId()
        );
        now(history);
        historyMapper.insert(history);  // useGeneratedKeys → history.id 자동 설정

        try {
            CspPeeringService cspService = resolveService(CspType.AWS);
            PeeringResultDto result = cspService.createPeering(request);
            history.activate(result.getPeeringConnectionId());
            history.setUpdatedAt(LocalDateTime.now());
            historyMapper.update(history);
            log.info("VPC Peering 생성 완료: id={}, peeringId={}", history.getId(), result.getPeeringConnectionId());
        } catch (BusinessException e) {
            // AWS 오류 시 FAILED로 상태 업데이트 후 예외 재throw
            history.markFailed();
            history.setUpdatedAt(LocalDateTime.now());
            historyMapper.update(history);
            throw e;
        }

        return PeeringHistoryResponse.from(history);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ACTIVE 상태 Peering 목록 페이지 조회.
     *
     * @param page 0-based 페이지 번호
     * @param size 페이지당 항목 수
     */
    public PageResult<PeeringHistoryResponse> findActive(int page, int size) {
        int offset = page * size;
        String status = PeeringStatus.ACTIVE.name();
        List<PeeringHistoryResponse> content = historyMapper.findByStatus(status, offset, size)
                .stream().map(PeeringHistoryResponse::from).collect(Collectors.toList());
        long total = historyMapper.countByStatus(status);
        return PageResult.of(content, page, size, total);
    }

    /**
     * 전체 Peering 이력 페이지 조회 (DELETED 포함).
     *
     * @param page 0-based 페이지 번호
     * @param size 페이지당 항목 수
     */
    public PageResult<PeeringHistoryResponse> findAll(int page, int size) {
        int offset = page * size;
        List<PeeringHistoryResponse> content = historyMapper.findAll(offset, size)
                .stream().map(PeeringHistoryResponse::from).collect(Collectors.toList());
        long total = historyMapper.countAll();
        return PageResult.of(content, page, size, total);
    }

    /**
     * ID로 Peering 이력 단건 조회.
     *
     * @param id Peering 이력 PK
     * @throws BusinessException 해당 ID가 없을 때 (404)
     */
    public PeeringHistoryResponse findById(Long id) {
        return PeeringHistoryResponse.from(getOrThrow(id));
    }

    /**
     * Peering ID에 연결된 스냅샷 목록 조회.
     * 먼저 Peering 이력 존재 여부를 검증한 후 스냅샷을 반환한다.
     *
     * @param historyId Peering 이력 PK
     * @throws BusinessException Peering 이력이 없을 때 (404)
     */
    public List<NetworkSnapshotResponse> findSnapshots(Long historyId) {
        getOrThrow(historyId);   // 이력 존재 여부 검증
        return snapshotMapper.findByPeeringHistoryId(historyId)
                .stream().map(NetworkSnapshotResponse::from).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MIGRATE (Jumphost 이전)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Jumphost 이전 1단계 — 신규 Jumphost 피어링(VPC-C ↔ VPC-B) 생성만 수행.
     *
     * <p>기존 피어링(VPC-A ↔ VPC-B)은 그대로 유지한다.
     * 클라이언트는 반환된 응답으로 신규 연결을 검증한 뒤 2단계를 호출한다:
     * <ul>
     *   <li>완료: {@code DELETE /api/v1/peerings/{existingPeeringId}} → 기존 피어링 삭제</li>
     *   <li>롤백: {@code DELETE /api/v1/peerings/{newPeering.id}} → 신규 피어링 삭제</li>
     * </ul>
     *
     * @param existingPeeringId 기존 Jumphost 피어링 이력 ID (VPC-A ↔ VPC-B)
     * @param request           신규 Jumphost(VPC-C) 정보
     * @return 신규 피어링 정보 + 기존 피어링 ID (아직 유지 중)
     * @throws BusinessException 기존 피어링 미존재, ACTIVE 아님, CIDR 겹침, AWS 오류 시
     */
    @Transactional
    public PeeringMigrationStartResponse startMigration(Long existingPeeringId, PeeringMigrationRequest request) {
        PeeringHistory existing = getOrThrow(existingPeeringId);

        if (existing.getStatus() != PeeringStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.PEERING_NOT_ACTIVE);
        }

        // 신규 Jumphost CIDR ↔ 기존 수락자(VPC-B) CIDR 겹침 사전 검증
        if (CidrUtils.isOverlapping(request.getNewRequesterCidr(), existing.getAccepterCidr())) {
            throw new BusinessException(ErrorCode.CIDR_OVERLAP,
                    String.format("%s ↔ %s", request.getNewRequesterCidr(), existing.getAccepterCidr()));
        }

        // 신규 피어링 이름 — 미지정 시 기존 이름에 prefix 추가
        String newName = StringUtils.hasText(request.getNewPeeringName())
                ? request.getNewPeeringName()
                : "migrated-" + (existing.getPeeringName() != null ? existing.getPeeringName() : existingPeeringId);

        // 수락자(VPC-B) 정보는 기존 피어링 이력에서 그대로 재사용
        PeeringCreateRequest createReq = PeeringCreateRequest.builder()
                .peeringName(newName)
                .requesterVpcId(request.getNewRequesterVpcId())
                .requesterCidr(request.getNewRequesterCidr())
                .requesterRouteTableId(request.getNewRequesterRouteTableId())
                .requesterAccountId(request.getNewRequesterAccountId())
                .accepterVpcId(existing.getAccepterVpcId())
                .accepterCidr(existing.getAccepterCidr())
                .accepterAccountId(existing.getAccepterAccountId())
                .accepterRouteTableId(existing.getAccepterRouteTableId())
                .accepterSecurityGroupId(existing.getAccepterSecurityGroupId())
                .build();

        // createPeering 전에 스냅샷을 수집해야 신규 CIDR 규칙이 추가되기 전 순수한 이전 상태를 기록할 수 있다.
        // createPeering 후에 수집하면 이미 SG에 신규 CIDR 규칙이 추가되어 AS-IS가 오염된다.
        Map<String, String> preMigSnapshots = null;
        try {
            CspPeeringService cspService = resolveService(CspType.AWS);
            preMigSnapshots = cspService.captureSnapshots(existing);
            log.info("이전 전 스냅샷 수집 완료: 항목수={}", preMigSnapshots.size());
        } catch (Exception e) {
            log.warn("이전 전 스냅샷 수집 실패 (이전은 계속 진행): {}", e.getMessage());
        }

        // 신규 피어링 생성 (VPC-C ↔ VPC-B) — 기존 피어링은 건드리지 않음
        PeeringHistoryResponse newPeering = createPeering(createReq);

        // 수집한 스냅샷을 신규 피어링 이력에 저장
        if (preMigSnapshots != null) {
            try {
                PeeringHistory newHistory = getOrThrow(newPeering.getId());
                saveSnapshots(newHistory, preMigSnapshots);
                log.info("이전 전 스냅샷 저장 완료: newPeeringId={}, 항목수={}", newPeering.getId(), preMigSnapshots.size());
            } catch (Exception e) {
                log.warn("이전 전 스냅샷 저장 실패: {}", e.getMessage());
            }
        }

        // 이전 이력 DB 기록 (IN_PROGRESS)
        JumphostMigrationHistory migration = JumphostMigrationHistory.create(existingPeeringId, newPeering.getId());
        migrationMapper.insert(migration);

        log.info("Jumphost 이전 1단계 완료: existingId={}, newPeeringId={}, migrationId={}",
                existingPeeringId, newPeering.getPeeringConnectionId(), migration.getId());

        return PeeringMigrationStartResponse.builder()
                .migrationId(migration.getId())
                .newPeering(newPeering)
                .existingPeeringId(existingPeeringId)
                .message(String.format(
                        "신규 Peering(%s) 생성 완료. 연결 검증 후 완료 또는 롤백을 선택하세요.",
                        newPeering.getPeeringConnectionId()))
                .build();
    }

    /**
     * Jumphost 이전 — 기존 Jumphost 피어링을 신규 Jumphost로 교체.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>기존 피어링 조회 + ACTIVE 상태 검증</li>
     *   <li>신규 요청자(VPC-C) CIDR ↔ 수락자(VPC-B) CIDR 겹침 검증</li>
     *   <li>신규 피어링(VPC-C ↔ VPC-B) 생성 — 수락자 정보는 기존 이력에서 재사용</li>
     *   <li>기존 피어링(VPC-A ↔ VPC-B) 삭제 (스냅샷 포함)</li>
     * </ol>
     *
     * <p>주의: 신규 생성과 기존 삭제는 같은 트랜잭션에 포함되지만 AWS API 호출은 트랜잭션 밖이다.
     * 신규 생성 성공 후 삭제 실패 시 AWS에 두 피어링이 공존할 수 있으므로 수동 정리가 필요할 수 있다.
     *
     * @param existingPeeringId 교체 대상 기존 피어링 이력 ID (VPC-A ↔ VPC-B)
     * @param request           신규 Jumphost(VPC-C) 정보
     * @return 신규 피어링 정보 + 삭제된 피어링 ID
     * @throws BusinessException 기존 피어링 미존재, ACTIVE 아님, CIDR 겹침, AWS 오류 시
     */
    @Transactional
    public PeeringMigrationResponse migratePeering(Long existingPeeringId, PeeringMigrationRequest request) {
        PeeringHistory existing = getOrThrow(existingPeeringId);

        if (existing.getStatus() != PeeringStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.PEERING_NOT_ACTIVE);
        }

        // 신규 Jumphost CIDR ↔ 기존 수락자(VPC-B) CIDR 겹침 사전 검증
        if (CidrUtils.isOverlapping(request.getNewRequesterCidr(), existing.getAccepterCidr())) {
            throw new BusinessException(ErrorCode.CIDR_OVERLAP,
                    String.format("%s ↔ %s", request.getNewRequesterCidr(), existing.getAccepterCidr()));
        }

        // 신규 피어링 이름 — 미지정 시 기존 이름에 prefix 추가
        String newName = StringUtils.hasText(request.getNewPeeringName())
                ? request.getNewPeeringName()
                : "migrated-" + (existing.getPeeringName() != null ? existing.getPeeringName() : existingPeeringId);

        // 수락자(VPC-B) 정보는 기존 피어링 이력에서 그대로 재사용
        PeeringCreateRequest createReq = PeeringCreateRequest.builder()
                .peeringName(newName)
                .requesterVpcId(request.getNewRequesterVpcId())
                .requesterCidr(request.getNewRequesterCidr())
                .requesterRouteTableId(request.getNewRequesterRouteTableId())
                .requesterAccountId(request.getNewRequesterAccountId())
                .accepterVpcId(existing.getAccepterVpcId())
                .accepterCidr(existing.getAccepterCidr())
                .accepterAccountId(existing.getAccepterAccountId())
                .accepterRouteTableId(existing.getAccepterRouteTableId())
                .accepterSecurityGroupId(existing.getAccepterSecurityGroupId())
                .build();

        // createPeering 전에 스냅샷을 수집해야 신규 CIDR 규칙이 추가되기 전 순수한 이전 상태를 기록할 수 있다.
        Map<String, String> preMigSnapshots = null;
        try {
            CspPeeringService cspService = resolveService(CspType.AWS);
            preMigSnapshots = cspService.captureSnapshots(existing);
            log.info("이전 전 스냅샷 수집 완료: 항목수={}", preMigSnapshots.size());
        } catch (Exception e) {
            log.warn("이전 전 스냅샷 수집 실패 (이전은 계속 진행): {}", e.getMessage());
        }

        // 신규 피어링 생성 (VPC-C ↔ VPC-B)
        PeeringHistoryResponse newPeering = createPeering(createReq);

        // 수집한 스냅샷을 신규 피어링 이력에 저장
        if (preMigSnapshots != null) {
            try {
                PeeringHistory newHistory = getOrThrow(newPeering.getId());
                saveSnapshots(newHistory, preMigSnapshots);
                log.info("이전 전 스냅샷 저장 완료: newPeeringId={}, 항목수={}", newPeering.getId(), preMigSnapshots.size());
            } catch (Exception e) {
                log.warn("이전 전 스냅샷 저장 실패: {}", e.getMessage());
            }
        }

        // 기존 피어링 삭제 (VPC-A ↔ VPC-B) — 삭제 시점 스냅샷 자동 저장 포함
        deletePeering(existingPeeringId);

        log.info("Jumphost 이전 완료: oldId={}, newId={}, newPeeringId={}",
                existingPeeringId, newPeering.getId(), newPeering.getPeeringConnectionId());

        return PeeringMigrationResponse.builder()
                .newPeering(newPeering)
                .deletedPeeringId(existingPeeringId)
                .message(String.format(
                        "Jumphost 이전 완료 — 신규 Peering(%s) 생성, 구 Peering ID(%d) 삭제",
                        newPeering.getPeeringConnectionId(), existingPeeringId))
                .build();
    }

    /**
     * Jumphost 이전 2단계 완료 — 기존 피어링(VPC-A ↔ VPC-B) 삭제 후 이전 이력을 COMPLETED 처리.
     *
     * <p>이전 이력이 IN_PROGRESS 상태일 때만 호출 가능하다.
     * 기존 피어링 삭제 실패 시 이력 상태는 변경되지 않는다.
     *
     * @param migrationId 이전 이력 PK
     * @return 완료된 이전 이력 응답 DTO
     * @throws BusinessException 이력 없음, 이미 완료/롤백된 이력, AWS 오류 시
     */
    @Transactional
    public MigrationHistoryResponse completeMigration(Long migrationId) {
        JumphostMigrationHistory migration = getMigrationOrThrow(migrationId);

        if (migration.getStatus() != MigrationStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.MIGRATION_ALREADY_FINISHED);
        }

        // 기존 피어링 삭제 (스냅샷 저장 포함)
        deletePeering(migration.getOldPeeringId());

        migration.complete();
        migrationMapper.update(migration);
        log.info("Jumphost 이전 완료: migrationId={}, oldPeeringId={}", migrationId, migration.getOldPeeringId());

        PeeringHistory oldPeering = getOrThrow(migration.getOldPeeringId());
        PeeringHistory newPeering = getOrThrow(migration.getNewPeeringId());

        // 이전 후 수락자 VPC 보안 그룹 현재 상태를 SECURITY_GROUP_POST 스냅샷으로 저장
        savePostMigrationSgSnapshot(newPeering);

        return MigrationHistoryResponse.from(migration, oldPeering, newPeering);
    }

    /**
     * Jumphost 이전 롤백.
     *
     * <p>이전 상태에 따라 동작이 다르다:
     * <ul>
     *   <li>IN_PROGRESS: 신규 피어링(VPC-C ↔ VPC-B)만 삭제 — 기존 피어링이 여전히 ACTIVE이므로 재생성 불필요</li>
     *   <li>COMPLETED: 신규 피어링 삭제 + 기존 피어링 정보로 재생성(VPC-A ↔ VPC-B 복원)</li>
     * </ul>
     *
     * @param migrationId 이전 이력 PK
     * @return 롤백된 이전 이력 응답 DTO
     * @throws BusinessException 이력 없음, 이미 롤백된 이력, AWS 오류 시
     */
    @Transactional
    public MigrationHistoryResponse rollbackMigration(Long migrationId) {
        JumphostMigrationHistory migration = getMigrationOrThrow(migrationId);

        if (migration.getStatus() == MigrationStatus.ROLLED_BACK) {
            throw new BusinessException(ErrorCode.MIGRATION_ALREADY_FINISHED);
        }

        PeeringHistory oldPeering = getOrThrow(migration.getOldPeeringId());
        PeeringHistory newPeering = getOrThrow(migration.getNewPeeringId());

        if (migration.getStatus() == MigrationStatus.COMPLETED) {
            // COMPLETED 롤백: 기존 피어링(VPC-A ↔ VPC-B) 재생성 후 신규 피어링 삭제
            PeeringCreateRequest restoreReq = PeeringCreateRequest.builder()
                    .peeringName("restored-" + (oldPeering.getPeeringName() != null
                            ? oldPeering.getPeeringName() : oldPeering.getId()))
                    .requesterVpcId(oldPeering.getRequesterVpcId())
                    .requesterCidr(oldPeering.getRequesterCidr())
                    .requesterRouteTableId(oldPeering.getRequesterRouteTableId())
                    .requesterAccountId(oldPeering.getRequesterAccountId())
                    .accepterVpcId(oldPeering.getAccepterVpcId())
                    .accepterCidr(oldPeering.getAccepterCidr())
                    .accepterAccountId(oldPeering.getAccepterAccountId())
                    .accepterRouteTableId(oldPeering.getAccepterRouteTableId())
                    .accepterSecurityGroupId(oldPeering.getAccepterSecurityGroupId())
                    .build();
            createPeering(restoreReq);
            log.info("롤백 — 기존 피어링 재생성 완료: oldPeeringId={}", oldPeering.getId());
        }

        // IN_PROGRESS·COMPLETED 공통: 신규 피어링 삭제
        deletePeering(newPeering.getId());

        migration.rollback();
        migrationMapper.update(migration);
        log.info("Jumphost 이전 롤백 완료: migrationId={}, newPeeringId={}", migrationId, newPeering.getId());

        // 최신 상태 재조회 (deletePeering 후 상태 변경됨)
        return MigrationHistoryResponse.from(migration,
                getOrThrow(migration.getOldPeeringId()),
                getOrThrow(migration.getNewPeeringId()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MIGRATION READ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 이전 이력 목록 페이지 조회 (최신순).
     *
     * @param page 0-based 페이지 번호
     * @param size 페이지당 항목 수
     */
    public PageResult<MigrationHistoryResponse> findMigrationHistory(int page, int size) {
        int offset = page * size;
        List<MigrationHistoryResponse> content = migrationMapper.findAll(offset, size)
                .stream()
                .map(m -> MigrationHistoryResponse.from(m,
                        getOrThrow(m.getOldPeeringId()),
                        getOrThrow(m.getNewPeeringId())))
                .collect(Collectors.toList());
        long total = migrationMapper.countAll();
        return PageResult.of(content, page, size, total);
    }

    /**
     * ID로 이전 이력 단건 조회.
     *
     * @param id 이전 이력 PK
     * @throws BusinessException 해당 ID가 없을 때 (404)
     */
    public MigrationHistoryResponse findMigrationById(Long id) {
        JumphostMigrationHistory m = getMigrationOrThrow(id);
        return MigrationHistoryResponse.from(m,
                getOrThrow(m.getOldPeeringId()),
                getOrThrow(m.getNewPeeringId()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VPC Peering 삭제.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>이력 조회 + DELETED 여부 검증</li>
     *   <li>삭제 전 스냅샷 수집 → DB 저장</li>
     *   <li>AWS 리소스 역순 삭제 (SG → 수락자 라우팅 → 요청자 라우팅 → Peering 연결)</li>
     *   <li>이력을 DELETED 상태로 UPDATE</li>
     * </ol>
     *
     * @param historyId 삭제할 Peering 이력 PK
     * @throws BusinessException 이력 없음, 이미 삭제됨, AWS 오류 시
     */
    @Transactional
    public void deletePeering(Long historyId) {
        PeeringHistory history = getOrThrow(historyId);

        if (history.getStatus() == PeeringStatus.DELETED) {
            throw new BusinessException(ErrorCode.PEERING_ALREADY_DELETED);
        }

        CspPeeringService cspService = resolveService(history.getCspType());

        // 삭제 전 스냅샷 수집 → DB 저장 (실패해도 삭제는 진행)
        Map<String, String> snapshots = cspService.captureSnapshots(history);
        saveSnapshots(history, snapshots);

        // AWS 리소스 역순 삭제
        cspService.deletePeering(history);

        history.markDeleted();
        history.setUpdatedAt(LocalDateTime.now());
        historyMapper.update(history);
        log.info("VPC Peering 삭제 완료: id={}, peeringId={}", historyId, history.getPeeringConnectionId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 이전 완료 후 수락자 VPC 보안 그룹 현재 상태를 SECURITY_GROUP_POST 스냅샷으로 저장.
     *
     * <p>신규 피어링 이력({@code newPeering.id})에 연결해 변경 내역 비교 팝업에서 이전·이후를 나란히 표시한다.
     * 저장 실패는 경고 로그만 남기고 이전 완료를 막지 않는다.
     *
     * @param newPeering 신규 피어링 이력 (SECURITY_GROUP_POST 스냅샷의 peeringHistoryId로 사용)
     */
    private void savePostMigrationSgSnapshot(PeeringHistory newPeering) {
        if (!StringUtils.hasText(newPeering.getAccepterVpcId())) {
            log.warn("이전 후 SG 스냅샷 건너뜀: accepterVpcId 없음 (newPeeringId={})", newPeering.getId());
            return;
        }
        try {
            CspPeeringService cspService = resolveService(CspType.AWS);
            String sgJson = cspService.captureVpcSecurityGroups(newPeering.getAccepterVpcId());
            LocalDateTime now = LocalDateTime.now();
            NetworkSnapshot snap = NetworkSnapshot.create(
                    newPeering.getId(), SnapshotDataType.SECURITY_GROUP_POST, sgJson);
            snap.setCreatedAt(now);
            snap.setUpdatedAt(now);
            snapshotMapper.insert(snap);
            log.info("이전 후 SG 스냅샷 저장 완료: newPeeringId={}", newPeering.getId());
        } catch (Exception e) {
            log.warn("이전 후 SG 스냅샷 저장 실패 (이전 완료는 유지됨): newPeeringId={}, msg={}",
                    newPeering.getId(), e.getMessage());
        }
    }

    /**
     * 스냅샷 맵을 순회하며 DB에 저장한다.
     * key 이름이 "ROUTE_TABLE"로 시작하면 ROUTE_TABLE 타입, 아니면 SECURITY_GROUP 타입으로 분류한다.
     *
     * @param history   연관 Peering 이력
     * @param snapshots key: 타입 식별자, value: JSON 문자열
     */
    private void saveSnapshots(PeeringHistory history, Map<String, String> snapshots) {
        LocalDateTime now = LocalDateTime.now();
        snapshots.forEach((typeKey, jsonData) -> {
            SnapshotDataType dataType = typeKey.startsWith("ROUTE_TABLE")
                    ? SnapshotDataType.ROUTE_TABLE
                    : SnapshotDataType.SECURITY_GROUP;
            NetworkSnapshot s = NetworkSnapshot.create(history.getId(), dataType, jsonData);
            s.setCreatedAt(now);
            s.setUpdatedAt(now);
            snapshotMapper.insert(s);
        });
    }

    /**
     * CspType에 해당하는 전략 구현체를 찾아 반환한다.
     *
     * @param cspType 대상 CSP 유형
     * @throws BusinessException 등록된 구현체가 없을 때
     */
    private CspPeeringService resolveService(CspType cspType) {
        return cspServices.stream()
                .filter(s -> s.getCspType() == cspType)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_CSP,
                        "지원하지 않는 CSP: " + cspType));
    }

    /**
     * ID로 Peering 이력 조회, 없으면 예외.
     *
     * @param id Peering 이력 PK
     * @throws BusinessException 해당 ID가 없을 때 (404)
     */
    private PeeringHistory getOrThrow(Long id) {
        return historyMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PEERING_NOT_FOUND));
    }

    /**
     * ID로 이전 이력 조회, 없으면 예외.
     *
     * @param id 이전 이력 PK
     * @throws BusinessException 해당 ID가 없을 때 (404)
     */
    private JumphostMigrationHistory getMigrationOrThrow(Long id) {
        return migrationMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MIGRATION_NOT_FOUND));
    }

    /**
     * PeeringHistory의 createdAt, updatedAt을 현재 시각으로 초기화한다.
     * JPA Auditing 미사용으로 서비스에서 직접 설정한다.
     */
    private void now(PeeringHistory h) {
        LocalDateTime now = LocalDateTime.now();
        h.setCreatedAt(now);
        h.setUpdatedAt(now);
    }
}
