package com.company.vpc.controller;

import com.company.vpc.common.ApiResponse;
import com.company.vpc.common.PageResult;
import com.company.vpc.dto.response.MigrationHistoryResponse;
import com.company.vpc.service.PeeringManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Jumphost 이전 이력 REST 컨트롤러.
 *
 * <p>기본 URL: {@code /api/v1/migrations}
 *
 * <p>2단계 이전 마법사의 완료·롤백 처리와 이력 조회를 담당한다.
 * 실제 AWS 작업과 DB 이력 관리는 {@link PeeringManagementService}에 위임한다.
 *
 * <ul>
 *   <li>{@code POST /{id}/complete} — 기존 피어링 삭제 + 이전 COMPLETED 처리</li>
 *   <li>{@code POST /{id}/rollback} — 신규 피어링 삭제(+ 기존 복원) + ROLLED_BACK 처리</li>
 *   <li>{@code GET /}              — 이전 이력 목록 페이지 조회</li>
 *   <li>{@code GET /{id}}          — 이전 이력 단건 조회</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/migrations")
@RequiredArgsConstructor
public class JumphostMigrationController {

    private final PeeringManagementService managementService;

    /**
     * 이전 완료 — 기존 피어링(VPC-A ↔ VPC-B) 삭제 후 이전 이력을 COMPLETED 처리.
     *
     * <p>이전 이력이 IN_PROGRESS 상태일 때만 호출 가능하다.
     *
     * @param id 이전 이력 PK
     * @return 200 OK + 완료된 이전 이력 정보
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<MigrationHistoryResponse>> complete(@PathVariable Long id) {
        MigrationHistoryResponse response = managementService.completeMigration(id);
        return ResponseEntity.ok(ApiResponse.ok("Jumphost 이전이 완료되었습니다. 기존 피어링이 삭제되었습니다.", response));
    }

    /**
     * 이전 롤백.
     *
     * <ul>
     *   <li>IN_PROGRESS 상태: 신규 피어링만 삭제 (기존 피어링은 이미 ACTIVE 유지)</li>
     *   <li>COMPLETED 상태: 신규 피어링 삭제 + 기존 피어링 재생성(VPC-A ↔ VPC-B 복원)</li>
     * </ul>
     *
     * @param id 이전 이력 PK
     * @return 200 OK + 롤백된 이전 이력 정보
     */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<ApiResponse<MigrationHistoryResponse>> rollback(@PathVariable Long id) {
        MigrationHistoryResponse response = managementService.rollbackMigration(id);
        return ResponseEntity.ok(ApiResponse.ok("이전이 롤백되었습니다.", response));
    }

    /**
     * 이전 이력 목록 페이지 조회 (최신순).
     *
     * @param page 0-based 페이지 번호 (기본값: 0)
     * @param size 페이지당 항목 수 (기본값: 20)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<MigrationHistoryResponse>>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(managementService.findMigrationHistory(page, size)));
    }

    /**
     * ID로 이전 이력 단건 조회.
     *
     * @param id 이전 이력 PK
     * @return 200 OK + 이전 이력, 없으면 GlobalExceptionHandler → 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MigrationHistoryResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(managementService.findMigrationById(id)));
    }
}
