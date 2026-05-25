package com.company.vpc.domain;

import com.company.vpc.domain.enums.MigrationStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Jumphost 이전 이력 도메인 객체.
 *
 * <p>jumphost_migration_history 테이블과 1:1 대응한다.
 * 2단계 이전 마법사({@code startMigration} → {@code completeMigration} 또는 {@code rollbackMigration})의
 * 실행 이력을 관리한다.
 *
 * <ul>
 *   <li>{@link #oldPeeringId} — 기존 Jumphost(VPC-A ↔ VPC-B) {@code peering_history.id}</li>
 *   <li>{@link #newPeeringId} — 신규 Jumphost(VPC-C ↔ VPC-B) {@code peering_history.id}</li>
 *   <li>{@link #status} — IN_PROGRESS → COMPLETED 또는 ROLLED_BACK</li>
 * </ul>
 *
 * <p>상태 전이는 의미있는 메서드({@link #complete}, {@link #rollback})를 통해서만 수행한다.
 */
@Getter
@Setter
@NoArgsConstructor
public class JumphostMigrationHistory {

    /** DB AUTO_INCREMENT PK */
    private Long id;

    /** 기존 피어링 (VPC-A ↔ VPC-B) ID — 이전 완료 후 DELETED 상태가 됨 */
    private Long oldPeeringId;

    /** 신규 피어링 (VPC-C ↔ VPC-B) ID — 이전 완료 후 ACTIVE 상태 */
    private Long newPeeringId;

    /** 이전 진행 상태 */
    private MigrationStatus status;

    /** 완료 또는 롤백 처리 시각 — IN_PROGRESS 중에는 null */
    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ─────────────────────────────────────────────────────────────────────────
    // 정적 팩토리
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 신규 이전 이력 생성 — 초기 상태는 IN_PROGRESS.
     *
     * @param oldPeeringId 기존 피어링 ID (VPC-A ↔ VPC-B)
     * @param newPeeringId 신규 피어링 ID (VPC-C ↔ VPC-B)
     */
    public static JumphostMigrationHistory create(Long oldPeeringId, Long newPeeringId) {
        JumphostMigrationHistory h = new JumphostMigrationHistory();
        h.oldPeeringId = oldPeeringId;
        h.newPeeringId = newPeeringId;
        h.status = MigrationStatus.IN_PROGRESS;
        LocalDateTime now = LocalDateTime.now();
        h.createdAt = now;
        h.updatedAt = now;
        return h;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 상태 전이
    // ─────────────────────────────────────────────────────────────────────────

    /** 기존 피어링 삭제 완료 후 COMPLETED 처리 */
    public void complete() {
        this.status = MigrationStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 이전 취소(롤백) 후 ROLLED_BACK 처리 */
    public void rollback() {
        this.status = MigrationStatus.ROLLED_BACK;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
