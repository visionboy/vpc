package com.company.vpc.domain;

import com.company.vpc.common.BaseTimeEntity;
import com.company.vpc.domain.enums.CspType;
import com.company.vpc.domain.enums.PeeringStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * VPC Peering 연결 이력 도메인 객체.
 *
 * <p>peering_history 테이블과 1:1로 대응하며 MyBatis resultMap으로 매핑된다.
 * JPA 미사용으로 setter가 허용되지만, 상태 변경은 의미있는 메서드를 통해서만 수행한다:
 * <ul>
 *   <li>{@link #activate} — 생성 성공 시 ACTIVE 처리</li>
 *   <li>{@link #markFailed} — 생성 실패 시 FAILED 처리</li>
 *   <li>{@link #markDeleted} — 삭제 완료 시 DELETED 처리</li>
 * </ul>
 *
 * <p>생성은 반드시 정적 팩토리 메서드 {@link #create}를 통해서만 한다.
 */
@Getter
@Setter
@NoArgsConstructor
public class PeeringHistory extends BaseTimeEntity {

    /** DB AUTO_INCREMENT PK — insert 후 MyBatis useGeneratedKeys로 자동 설정 */
    private Long id;

    /** AWS VPC Peering Connection ID (예: pcx-0abc123def456) — 생성 후 설정 */
    private String peeringConnectionId;

    /** 사용자 정의 피어링 이름 */
    private String peeringName;

    /** CSP 유형 — 현재는 항상 AWS */
    private CspType cspType;

    // ── 요청자(Requester) 측 정보 ──────────────────────────────────────────────

    /** 요청자 VPC ID (예: vpc-0abc123) */
    private String requesterVpcId;

    /** 요청자 VPC CIDR (예: 10.1.0.0/16) */
    private String requesterCidr;

    /** 요청자 AWS 계정 ID (예: 123456789012) */
    private String requesterAccountId;

    /** 요청자 라우팅 테이블 ID — 수락자 CIDR로 가는 경로가 추가된다 */
    private String requesterRouteTableId;

    // ── 수락자(Accepter) 측 정보 ──────────────────────────────────────────────

    /** 수락자 VPC ID (예: vpc-0def456) */
    private String accepterVpcId;

    /** 수락자 VPC CIDR (예: 10.2.0.0/16) */
    private String accepterCidr;

    /** 수락자 AWS 계정 ID */
    private String accepterAccountId;

    /** 수락자 라우팅 테이블 ID — 요청자 CIDR로 가는 경로가 추가된다 */
    private String accepterRouteTableId;

    /** 수락자 보안 그룹 ID — ICMP 인바운드 규칙이 자동 추가된다 (선택) */
    private String accepterSecurityGroupId;

    // ── 상태 ──────────────────────────────────────────────────────────────────

    /** 현재 Peering 상태 (PENDING → ACTIVE 또는 FAILED, ACTIVE → DELETED) */
    private PeeringStatus status;

    /** 소프트 삭제 일시 — DELETED 상태 전환 시 설정 */
    private LocalDateTime deletedAt;

    // ── 정적 팩토리 ──────────────────────────────────────────────────────────

    /**
     * 신규 Peering 이력 생성 — 초기 상태는 PENDING.
     * createdAt/updatedAt은 서비스 계층에서 별도로 설정한다.
     */
    public static PeeringHistory create(String peeringName, CspType cspType,
                                        String requesterVpcId, String requesterCidr,
                                        String requesterAccountId, String requesterRouteTableId,
                                        String accepterVpcId, String accepterCidr,
                                        String accepterAccountId, String accepterRouteTableId,
                                        String accepterSecurityGroupId) {
        PeeringHistory h = new PeeringHistory();
        h.peeringName = peeringName;
        h.cspType = cspType;
        h.requesterVpcId = requesterVpcId;
        h.requesterCidr = requesterCidr;
        h.requesterAccountId = requesterAccountId;
        h.requesterRouteTableId = requesterRouteTableId;
        h.accepterVpcId = accepterVpcId;
        h.accepterCidr = accepterCidr;
        h.accepterAccountId = accepterAccountId;
        h.accepterRouteTableId = accepterRouteTableId;
        h.accepterSecurityGroupId = accepterSecurityGroupId;
        h.status = PeeringStatus.PENDING;
        return h;
    }

    // ── 상태 전이 메서드 ─────────────────────────────────────────────────────

    /**
     * AWS Peering 생성 완료 후 ACTIVE 처리.
     *
     * @param peeringConnectionId AWS에서 발급한 pcx-xxxxx ID
     */
    public void activate(String peeringConnectionId) {
        this.peeringConnectionId = peeringConnectionId;
        this.status = PeeringStatus.ACTIVE;
    }

    /** AWS API 오류로 생성 실패 시 FAILED 처리 — 보상 트랜잭션 실행 후 호출 */
    public void markFailed() {
        this.status = PeeringStatus.FAILED;
    }

    /** 역순 삭제 완료 후 DELETED 처리 — deletedAt도 함께 기록 */
    public void markDeleted() {
        this.status = PeeringStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }
}
