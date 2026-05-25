package com.company.vpc.mapper;

import com.company.vpc.domain.PeeringHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * peering_history 테이블 MyBatis Mapper 인터페이스.
 *
 * <p>SQL은 {@code resources/mapper/PeeringHistoryMapper.xml}에 정의되어 있다.
 * {@code map-underscore-to-camel-case: true} 설정으로 DB 컬럼(snake_case) → Java 필드(camelCase) 자동 변환된다.
 *
 * <p>주의: insert 시 {@code useGeneratedKeys="true"}로 AUTO_INCREMENT PK가
 * {@link PeeringHistory#getId()}에 자동으로 설정된다.
 */
@Mapper
public interface PeeringHistoryMapper {

    /**
     * 신규 Peering 이력 INSERT.
     * INSERT 후 DB에서 생성된 PK가 {@code history.id}에 자동 설정된다.
     *
     * @param history 삽입할 Peering 이력 (id는 null로 넘기면 됨)
     */
    void insert(PeeringHistory history);

    /**
     * Peering 이력 UPDATE — status, peeringConnectionId, deletedAt, updatedAt 갱신.
     *
     * @param history 수정할 Peering 이력 (id가 WHERE 조건)
     */
    void update(PeeringHistory history);

    /**
     * ID로 단건 조회.
     *
     * @param id PK
     * @return 존재하면 Optional에 담아 반환, 없으면 {@code Optional.empty()}
     */
    Optional<PeeringHistory> findById(@Param("id") Long id);

    /**
     * 상태별 목록 조회 (페이지네이션).
     *
     * @param status PeeringStatus.name() 문자열 (예: "ACTIVE")
     * @param offset 건너뛸 항목 수 (= page * size)
     * @param limit  가져올 최대 항목 수
     */
    List<PeeringHistory> findByStatus(@Param("status") String status,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    /**
     * 상태별 전체 건수 조회 — 페이지네이션의 totalElements 산출에 사용.
     *
     * @param status PeeringStatus.name() 문자열
     */
    long countByStatus(@Param("status") String status);

    /**
     * 전체 이력 목록 조회 (페이지네이션) — 삭제된 항목도 포함.
     *
     * @param offset 건너뛸 항목 수
     * @param limit  가져올 최대 항목 수
     */
    List<PeeringHistory> findAll(@Param("offset") int offset,
                                 @Param("limit") int limit);

    /**
     * 전체 이력 건수 — 페이지네이션의 totalElements 산출에 사용.
     */
    long countAll();
}
