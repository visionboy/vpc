package com.company.vpc.domain.enums;

/**
 * 네트워크 스냅샷의 데이터 종류.
 *
 * <p>VPC Peering 삭제 또는 Jumphost 이전 시 감사·비교 목적으로 현재 상태를 JSON으로 저장한다.
 * network_snapshot 테이블의 data_type 컬럼(VARCHAR(30))에 name() 문자열로 저장된다.
 */
public enum SnapshotDataType {

    /** 라우팅 테이블(Route Table) 상태 스냅샷 — 요청자·수락자 각각 저장 */
    ROUTE_TABLE,

    /**
     * 보안 그룹 스냅샷 — 수락자 VPC 내 모든 EC2 인스턴스 SG 인바운드·아웃바운드 규칙.
     * JSON 배열({@code List<SecurityGroupDetailDto>} 호환 형식)으로 저장된다.
     * Jumphost 이전에서는 이전 직전(PRE) 상태를 의미한다.
     */
    SECURITY_GROUP,

    /**
     * 이전 완료 후 보안 그룹 스냅샷 — {@link #SECURITY_GROUP}과 동일 형식.
     * Jumphost 이전 완료 시점의 수락자 VPC SG 상태(POST)를 저장해 변경 전·후 비교에 사용한다.
     */
    SECURITY_GROUP_POST
}
