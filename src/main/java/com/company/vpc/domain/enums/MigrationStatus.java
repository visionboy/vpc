package com.company.vpc.domain.enums;

/**
 * Jumphost 이전 이력의 진행 상태값.
 */
public enum MigrationStatus {

    /** startMigration 완료 — 신규 피어링 생성됨, 기존 피어링 유지 중, 검증 대기 */
    IN_PROGRESS,

    /** completeMigration 완료 — 기존 피어링 삭제, 신규 피어링 활성 */
    COMPLETED,

    /** rollbackMigration 완료 — 신규 피어링 삭제, 기존 피어링 복원(또는 유지) */
    ROLLED_BACK
}
