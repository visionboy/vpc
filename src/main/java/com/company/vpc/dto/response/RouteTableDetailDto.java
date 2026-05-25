package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 라우팅 테이블 상세 응답 DTO — 경로(Route) 항목 목록 포함.
 *
 * <p>변경 내역 비교 화면에서 이후(TO-BE) 현재 상태를 스냅샷({@link NetworkSnapshotResponse})과
 * 동일한 구조로 반환하기 위해 사용한다.
 * 이를 통해 프론트엔드가 필드 이름 변환 없이 이전·이후 데이터를 나란히 비교할 수 있다.
 */
@Getter
@Builder
public class RouteTableDetailDto {

    /** 라우팅 테이블 ID (예: rtb-0abc123) */
    private String routeTableId;

    /** 이 라우팅 테이블이 속한 VPC ID */
    private String vpcId;

    /** 현재 라우팅 테이블에 등록된 경로 목록 */
    private List<RouteEntry> routes;

    /**
     * 개별 라우팅 경로 DTO.
     *
     * <p>대표적인 경로 유형:
     * <ul>
     *   <li>로컬 라우팅 — {@code gatewayId=local}</li>
     *   <li>Peering 경로 — {@code vpcPeeringConnectionId} 존재</li>
     *   <li>인터넷 게이트웨이 — {@code gatewayId=igw-xxx}</li>
     * </ul>
     */
    @Getter
    @Builder
    public static class RouteEntry {

        /** 대상 IPv4 CIDR 블록 (예: 10.3.0.0/16) — 접두사 목록 라우팅이면 null */
        private String destinationCidrBlock;

        /** 경로가 향하는 VPC Peering 연결 ID (예: pcx-0abc123) — Peering 경로에만 존재 */
        private String vpcPeeringConnectionId;

        /** 게이트웨이 ID (local, igw-xxx 등) — Peering 경로에서는 null */
        private String gatewayId;

        /** 경로 상태 (active, blackhole) */
        private String state;
    }
}
