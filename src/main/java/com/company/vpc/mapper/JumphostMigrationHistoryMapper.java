package com.company.vpc.mapper;

import com.company.vpc.domain.JumphostMigrationHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * jumphost_migration_history 테이블 MyBatis Mapper 인터페이스.
 *
 * <p>SQL은 {@code resources/mapper/JumphostMigrationHistoryMapper.xml}에 정의되어 있다.
 */
@Mapper
public interface JumphostMigrationHistoryMapper {

    /**
     * 신규 이전 이력 INSERT.
     * INSERT 후 생성된 PK가 {@code history.id}에 자동 설정된다.
     *
     * @param history 삽입할 이전 이력
     */
    void insert(JumphostMigrationHistory history);

    /**
     * 이전 이력 UPDATE — status, completedAt, updatedAt 갱신.
     *
     * @param history 수정할 이전 이력 (id가 WHERE 조건)
     */
    void update(JumphostMigrationHistory history);

    /**
     * ID로 단건 조회.
     *
     * @param id PK
     * @return 존재하면 Optional에 담아 반환, 없으면 {@code Optional.empty()}
     */
    Optional<JumphostMigrationHistory> findById(@Param("id") Long id);

    /**
     * 전체 이전 이력 목록 조회 (페이지네이션, 최신순).
     *
     * @param offset 건너뛸 항목 수 (= page * size)
     * @param limit  가져올 최대 항목 수
     */
    List<JumphostMigrationHistory> findAll(@Param("offset") int offset,
                                           @Param("limit") int limit);

    /**
     * 전체 이전 이력 건수.
     */
    long countAll();
}
