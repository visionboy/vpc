package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Jumphost 이전 1단계(신규 피어링 생성) 결과 응답 DTO.
 *
 * <p>신규 피어링 생성만 완료된 상태 — 기존 피어링은 아직 유지 중.
 * 클라이언트는 이 응답을 받은 후 연결 검증을 수행하고
 * 완료({@code POST /api/v1/migrations/{migrationId}/complete}) 또는
 * 롤백({@code POST /api/v1/migrations/{migrationId}/rollback})을 호출한다.
 */
@Getter
@Builder
public class PeeringMigrationStartResponse {

    /** 이전 이력 ID (JumphostMigrationHistory.id) — 완료·롤백 API 호출에 사용 */
    private Long migrationId;

    /** 새로 생성된 피어링 (VPC-C ↔ VPC-B) 전체 정보 */
    private PeeringHistoryResponse newPeering;

    /** 기존 피어링 (VPC-A ↔ VPC-B) DB ID — 아직 유지 중 */
    private Long existingPeeringId;

    /** 처리 결과 안내 메시지 */
    private String message;
}
