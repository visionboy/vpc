package com.company.vpc.domain.enums;

/**
 * VPC Peering 연결의 생명주기 상태값.
 *
 * <p>상태 전이 흐름:
 * <pre>
 * PENDING → ACTIVE  (생성 성공)
 * PENDING → FAILED  (생성 실패 — 보상 트랜잭션 실행됨)
 * ACTIVE  → DELETED (삭제 완료)
 * </pre>
 *
 * <p>DB 컬럼 타입: VARCHAR — {@code EnumTypeHandler}로 name() 문자열을 저장한다.
 */
public enum PeeringStatus {

    /** AWS createVpcPeeringConnection 요청 후 수락 대기 또는 처리 중 */
    PENDING,

    /** 5단계 생성 완료 — 라우팅·SG 규칙까지 모두 설정된 활성 상태 */
    ACTIVE,

    /** 역순 삭제 완료 — 라우팅·SG·Peering 연결 모두 해제 */
    DELETED,

    /** 생성 또는 삭제 도중 AWS API 오류로 실패한 상태 */
    FAILED
}
