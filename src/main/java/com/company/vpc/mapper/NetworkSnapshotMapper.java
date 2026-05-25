package com.company.vpc.mapper;

import com.company.vpc.domain.NetworkSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * network_snapshot 테이블 MyBatis Mapper 인터페이스.
 *
 * <p>SQL은 {@code resources/mapper/NetworkSnapshotMapper.xml}에 정의되어 있다.
 * 스냅샷은 Peering 삭제 전에만 INSERT되며 이후 수정되지 않는(불변) 감사 데이터이다.
 *
 * <p>하나의 Peering 이력에 대해 최대 3개의 스냅샷이 저장된다:
 * 요청자 라우팅 테이블, 수락자 라우팅 테이블, 수락자 보안 그룹.
 */
@Mapper
public interface NetworkSnapshotMapper {

    /**
     * 스냅샷 INSERT.
     * INSERT 후 DB에서 생성된 PK가 {@code snapshot.id}에 자동 설정된다.
     *
     * @param snapshot 삽입할 스냅샷 (id는 null로 넘기면 됨)
     */
    void insert(NetworkSnapshot snapshot);

    /**
     * Peering 이력 ID로 연관 스냅샷 전체 조회.
     * data_type 오름차순으로 반환 (ROUTE_TABLE → SECURITY_GROUP 순).
     *
     * @param peeringHistoryId 조회할 Peering 이력 ID
     */
    List<NetworkSnapshot> findByPeeringHistoryId(@Param("peeringHistoryId") Long peeringHistoryId);
}
