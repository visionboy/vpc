package com.company.vpc.controller;

import com.company.vpc.common.ApiResponse;
import com.company.vpc.common.PageResult;
import com.company.vpc.dto.request.PeeringCreateRequest;
import com.company.vpc.dto.request.PeeringMigrationRequest;
import com.company.vpc.dto.response.NetworkSnapshotResponse;
import com.company.vpc.dto.response.PeeringHistoryResponse;
import com.company.vpc.dto.response.PeeringMigrationResponse;
import com.company.vpc.dto.response.PeeringMigrationStartResponse;
import com.company.vpc.service.PeeringManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * VPC Peering 생명주기 REST 컨트롤러.
 *
 * <p>기본 URL: {@code /api/v1/peerings}
 *
 * <p>모든 비즈니스 로직은 {@link PeeringManagementService}에 위임한다.
 * 컨트롤러는 요청 파싱·응답 포맷팅·HTTP 상태 코드 설정만 담당한다.
 *
 * <p>예외는 {@link com.company.vpc.exception.GlobalExceptionHandler}에서 일괄 처리하므로
 * 컨트롤러에 try-catch를 작성하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/peerings")
@RequiredArgsConstructor
@Validated
public class VpcPeeringController {

    private final PeeringManagementService managementService;

    /**
     * VPC Peering 생성.
     * AWS API 5단계 완료 후 DB에 ACTIVE 상태로 이력을 저장한다.
     *
     * @param request 생성 요청 DTO (@Valid로 Bean Validation 적용)
     * @return 201 Created + 생성된 Peering 이력
     */
    @PostMapping
    public ResponseEntity<ApiResponse<PeeringHistoryResponse>> create(
            @RequestBody @Valid PeeringCreateRequest request) {
        PeeringHistoryResponse response = managementService.createPeering(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("VPC Peering이 성공적으로 생성되었습니다.", response));
    }

    /**
     * ACTIVE 상태 Peering 목록 페이지 조회.
     *
     * @param page 0-based 페이지 번호 (기본값: 0)
     * @param size 페이지당 항목 수 (기본값: 20)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<PeeringHistoryResponse>>> findActive(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(managementService.findActive(page, size)));
    }

    /**
     * 전체 Peering 이력 페이지 조회 — DELETED 상태 포함.
     *
     * @param page 0-based 페이지 번호 (기본값: 0)
     * @param size 페이지당 항목 수 (기본값: 20)
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PageResult<PeeringHistoryResponse>>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(managementService.findAll(page, size)));
    }

    /**
     * ID로 Peering 이력 단건 조회.
     *
     * @param id Peering 이력 PK
     * @return 200 OK + Peering 이력, 없으면 GlobalExceptionHandler → 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PeeringHistoryResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(managementService.findById(id)));
    }

    /**
     * Peering ID에 연결된 네트워크 스냅샷 조회.
     * 스냅샷은 Peering 삭제 시점에 캡처된 라우팅 테이블·보안 그룹 JSON 데이터이다.
     *
     * @param id Peering 이력 PK
     */
    @GetMapping("/{id}/snapshots")
    public ResponseEntity<ApiResponse<List<NetworkSnapshotResponse>>> findSnapshots(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(managementService.findSnapshots(id)));
    }

    /**
     * Jumphost 이전 1단계 — 신규 Jumphost 피어링(VPC-C ↔ VPC-B)만 생성, 기존 피어링 유지.
     *
     * <p>다음 단계 (클라이언트가 연결 검증 후 선택):
     * <ul>
     *   <li>완료: {@code DELETE /api/v1/peerings/{data.existingPeeringId}}</li>
     *   <li>롤백: {@code DELETE /api/v1/peerings/{data.newPeering.id}}</li>
     * </ul>
     *
     * @param id      기존 Jumphost 피어링 이력 PK (VPC-A ↔ VPC-B)
     * @param request 신규 Jumphost(VPC-C) 정보
     * @return 201 Created + 신규 피어링 정보 및 기존 피어링 ID
     */
    @PostMapping("/{id}/migrate/start")
    public ResponseEntity<ApiResponse<PeeringMigrationStartResponse>> migrateStart(
            @PathVariable Long id,
            @RequestBody @Valid PeeringMigrationRequest request) {
        PeeringMigrationStartResponse response = managementService.startMigration(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("신규 Jumphost 피어링이 생성되었습니다. 연결 검증 후 완료 또는 롤백을 선택하세요.", response));
    }

    /**
     * Jumphost 이전 (단일 단계) — 기존 Jumphost 피어링(VPC-A ↔ VPC-B)을 신규(VPC-C ↔ VPC-B)로 교체.
     *
     * <p>처리 순서: 신규 피어링 생성 → 기존 피어링 삭제 (스냅샷 포함).
     *
     * @param id      교체 대상 기존 피어링 이력 PK (VPC-A ↔ VPC-B)
     * @param request 신규 Jumphost(VPC-C) 정보
     * @return 200 OK + 신규 피어링 정보 및 삭제 결과
     */
    @PostMapping("/{id}/migrate")
    public ResponseEntity<ApiResponse<PeeringMigrationResponse>> migrate(
            @PathVariable Long id,
            @RequestBody @Valid PeeringMigrationRequest request) {
        PeeringMigrationResponse response = managementService.migratePeering(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Jumphost 이전이 완료되었습니다.", response));
    }

    /**
     * VPC Peering 해제 + AWS 리소스 역순 삭제 + 삭제 전 스냅샷 저장.
     *
     * @param id 삭제할 Peering 이력 PK
     * @return 204 No Content (응답 body 없음)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        managementService.deletePeering(id);
        return ResponseEntity.noContent().build();
    }
}
