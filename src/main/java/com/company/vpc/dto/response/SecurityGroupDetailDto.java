package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 보안 그룹 상세 응답 DTO — 인바운드·아웃바운드 규칙 포함.
 *
 * <p>EC2/RDS 인스턴스 행에서 [상세] 버튼 클릭 시 모달에 표시되는 데이터이다.
 * 하나의 인스턴스에 여러 보안 그룹이 연결될 수 있으므로 List 형태로 반환된다.
 */
@Getter
@Builder
public class SecurityGroupDetailDto {

    /** 보안 그룹 ID (예: sg-0abc123) */
    private String groupId;

    /** 보안 그룹 이름 */
    private String groupName;

    /** 보안 그룹 설명 */
    private String description;

    /** 인바운드(Ingress) 규칙 목록 */
    private List<SgRule> inboundRules;

    /** 아웃바운드(Egress) 규칙 목록 */
    private List<SgRule> outboundRules;

    /**
     * 보안 그룹 단일 규칙 DTO.
     *
     * <p>source 필드는 규칙 유형에 따라 다른 값을 담는다:
     * <ul>
     *   <li>IPv4 CIDR (예: 0.0.0.0/0)</li>
     *   <li>IPv6 CIDR (예: ::/0)</li>
     *   <li>보안 그룹 ID 참조 (예: sg-0abc123) — 같은 VPC 내 SG 간 허용에 사용</li>
     * </ul>
     */
    @Getter
    @Builder
    public static class SgRule {

        /** 프로토콜 (TCP / UDP / ICMP / ALL) — "-1"은 ALL로 변환 */
        private String protocol;

        /** 시작 포트 (-1은 모든 포트 또는 ICMP 유형 전체) */
        private Integer fromPort;

        /** 종료 포트 (-1은 모든 포트 또는 ICMP 코드 전체) */
        private Integer toPort;

        /** 트래픽 출처 — CIDR 또는 참조 보안 그룹 ID */
        private String source;

        /** 규칙 설명 (AWS 콘솔 설명 필드) */
        private String description;
    }
}
