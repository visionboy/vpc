package com.company.vpc.dto.response;

import com.company.vpc.domain.PeeringHistory;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Peering 이력 응답 DTO.
 *
 * <p>Entity → DTO 변환은 반드시 {@link #from} 정적 팩토리 메서드만 사용한다.
 * 서비스·컨트롤러에서 직접 빌더를 호출하지 않는다.
 *
 * <p>민감 정보(access-key 등)는 포함하지 않으며 클라이언트에 안전하게 노출 가능한 필드만 담는다.
 */
@Getter
@Builder
public class PeeringHistoryResponse {

    /** DB PK */
    private Long id;

    /** AWS Peering Connection ID (예: pcx-0abc123) — 생성 완료 전까지 null */
    private String peeringConnectionId;

    /** 사용자 정의 Peering 이름 */
    private String peeringName;

    /** CSP 유형 (현재 항상 "AWS") */
    private String cspType;

    /** 요청자 VPC ID */
    private String requesterVpcId;

    /** 요청자 VPC CIDR */
    private String requesterCidr;

    /** 수락자 VPC ID */
    private String accepterVpcId;

    /** 수락자 VPC CIDR */
    private String accepterCidr;

    /** 수락자 보안 그룹 ID */
    private String accepterSecurityGroupId;

    /** 요청자 라우팅 테이블 ID */
    private String requesterRouteTableId;

    /** 수락자 라우팅 테이블 ID */
    private String accepterRouteTableId;

    /** Peering 상태 (PENDING / ACTIVE / DELETED / FAILED) */
    private String status;

    /** 생성 일시 */
    private LocalDateTime createdAt;

    /** 삭제 일시 — DELETED 상태 전환 시 설정, 그 외 null */
    private LocalDateTime deletedAt;

    /**
     * {@link PeeringHistory} Entity → {@link PeeringHistoryResponse} DTO 변환.
     * DTO 변환 로직을 DTO 내부에 캡슐화해 서비스 계층을 단순하게 유지한다.
     *
     * @param h 변환할 Peering 이력 Entity
     */
    public static PeeringHistoryResponse from(PeeringHistory h) {
        return PeeringHistoryResponse.builder()
                .id(h.getId())
                .peeringConnectionId(h.getPeeringConnectionId())
                .peeringName(h.getPeeringName())
                .cspType(h.getCspType().name())
                .requesterVpcId(h.getRequesterVpcId())
                .requesterCidr(h.getRequesterCidr())
                .accepterVpcId(h.getAccepterVpcId())
                .accepterCidr(h.getAccepterCidr())
                .accepterSecurityGroupId(h.getAccepterSecurityGroupId())
                .requesterRouteTableId(h.getRequesterRouteTableId())
                .accepterRouteTableId(h.getAccepterRouteTableId())
                .status(h.getStatus().name())
                .createdAt(h.getCreatedAt())
                .deletedAt(h.getDeletedAt())
                .build();
    }
}
