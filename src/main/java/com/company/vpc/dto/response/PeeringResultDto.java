package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * AWS Peering 생성 작업 결과 DTO.
 *
 * <p>{@link com.company.vpc.service.CspPeeringService#createPeering}의 반환 타입으로,
 * CSP 구현체에서 서비스 계층으로 결과를 전달하는 내부 DTO이다.
 * 최종 HTTP 응답은 {@link PeeringHistoryResponse}로 변환되어 반환된다.
 */
@Getter
@Builder
public class PeeringResultDto {

    /** AWS에서 발급한 Peering Connection ID (예: pcx-0abc123def456) */
    private String peeringConnectionId;

    /** 처리 결과 상태 (예: "ACTIVE") */
    private String status;

    /** 처리 결과 메시지 */
    private String message;
}
