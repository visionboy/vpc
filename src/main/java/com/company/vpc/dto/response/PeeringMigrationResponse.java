package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Jumphost 이전 결과 응답 DTO.
 *
 * <p>이전 완료 후 신규 피어링 정보와 삭제된 구 피어링 ID를 함께 반환한다.
 */
@Getter
@Builder
public class PeeringMigrationResponse {

    /** 새로 생성된 피어링 (VPC-C ↔ VPC-B) 전체 정보 */
    private PeeringHistoryResponse newPeering;

    /** 삭제된 구 피어링 (VPC-A ↔ VPC-B) DB ID */
    private Long deletedPeeringId;

    /** 처리 결과 요약 메시지 */
    private String message;
}
