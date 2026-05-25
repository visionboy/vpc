package com.company.vpc.dto.response;

import com.company.vpc.domain.JumphostMigrationHistory;
import com.company.vpc.domain.PeeringHistory;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Jumphost 이전 이력 응답 DTO.
 *
 * <p>이전 전 피어링(AS-IS)과 이전 후 피어링(TO-BE)의 전체 정보를 포함한다.
 * Entity → DTO 변환은 반드시 {@link #from} 정적 팩토리를 사용한다.
 */
@Getter
@Builder
public class MigrationHistoryResponse {

    /** 이전 이력 PK */
    private Long id;

    /** 기존 피어링 정보 (VPC-A ↔ VPC-B) — 이전 완료 후 DELETED 상태 */
    private PeeringHistoryResponse oldPeering;

    /** 신규 피어링 정보 (VPC-C ↔ VPC-B) — 이전 완료 후 ACTIVE 상태 */
    private PeeringHistoryResponse newPeering;

    /** 이전 진행 상태 (IN_PROGRESS / COMPLETED / ROLLED_BACK) */
    private String status;

    /** 완료 또는 롤백 처리 시각 — IN_PROGRESS 중에는 null */
    private LocalDateTime completedAt;

    /** 이전 시작(startMigration) 시각 */
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Entity 조합으로 DTO 생성.
     *
     * @param m          이전 이력 엔티티
     * @param oldPeering 기존 피어링 엔티티
     * @param newPeering 신규 피어링 엔티티
     */
    public static MigrationHistoryResponse from(JumphostMigrationHistory m,
                                                PeeringHistory oldPeering,
                                                PeeringHistory newPeering) {
        return MigrationHistoryResponse.builder()
                .id(m.getId())
                .oldPeering(PeeringHistoryResponse.from(oldPeering))
                .newPeering(PeeringHistoryResponse.from(newPeering))
                .status(m.getStatus().name())
                .completedAt(m.getCompletedAt())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}
