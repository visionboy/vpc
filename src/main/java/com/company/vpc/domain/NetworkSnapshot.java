package com.company.vpc.domain;

import com.company.vpc.common.BaseTimeEntity;
import com.company.vpc.domain.enums.SnapshotDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * VPC Peering 삭제 전 캡처한 네트워크 구성 스냅샷 도메인 객체.
 *
 * <p>network_snapshot 테이블과 1:1로 대응하며 MyBatis resultMap으로 매핑된다.
 * Peering 삭제 시 라우팅 테이블·보안 그룹의 현재 상태를 JSON으로 저장해
 * 롤백 또는 감사(audit) 용도로 활용한다.
 *
 * <p>하나의 Peering에 대해 여러 스냅샷이 저장될 수 있다:
 * <ul>
 *   <li>ROUTE_TABLE_REQUESTER — 요청자 라우팅 테이블</li>
 *   <li>ROUTE_TABLE_ACCEPTER — 수락자 라우팅 테이블</li>
 *   <li>SECURITY_GROUP — 수락자 보안 그룹</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
public class NetworkSnapshot extends BaseTimeEntity {

    /** DB AUTO_INCREMENT PK */
    private Long id;

    /** 이 스냅샷이 속한 Peering 이력 ID (peering_history.id FK) */
    private Long peeringHistoryId;

    /** 스냅샷 데이터 종류 (ROUTE_TABLE / SECURITY_GROUP) */
    private SnapshotDataType dataType;

    /** AWS API 응답을 직렬화한 JSON 문자열 */
    private String snapshotData;

    /**
     * 스냅샷 생성 팩토리.
     * createdAt/updatedAt은 서비스 계층에서 별도로 설정한다.
     *
     * @param peeringHistoryId 연결된 Peering 이력 ID
     * @param dataType         스냅샷 종류
     * @param snapshotData     JSON 직렬화된 AWS 리소스 상태
     */
    public static NetworkSnapshot create(Long peeringHistoryId, SnapshotDataType dataType,
                                         String snapshotData) {
        NetworkSnapshot s = new NetworkSnapshot();
        s.peeringHistoryId = peeringHistoryId;
        s.dataType = dataType;
        s.snapshotData = snapshotData;
        return s;
    }
}
