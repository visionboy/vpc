-- Jumphost 이전 이력 테이블
-- 2단계 이전 마법사(startMigration)의 실행 이력을 관리한다.
-- old_peering_id = 기존 Jumphost(VPC-A ↔ VPC-B), new_peering_id = 신규 Jumphost(VPC-C ↔ VPC-B)
CREATE TABLE IF NOT EXISTS jumphost_migration_history
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    old_peering_id BIGINT      NOT NULL COMMENT '기존 피어링 (VPC-A ↔ VPC-B) ID',
    new_peering_id BIGINT      NOT NULL COMMENT '신규 피어링 (VPC-C ↔ VPC-B) ID',
    status         VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS / COMPLETED / ROLLED_BACK',
    completed_at   DATETIME    NULL     COMMENT '완료 또는 롤백 처리 시각',
    created_at     DATETIME    NOT NULL,
    updated_at     DATETIME    NOT NULL,
    CONSTRAINT fk_jmh_old FOREIGN KEY (old_peering_id) REFERENCES peering_history (id),
    CONSTRAINT fk_jmh_new FOREIGN KEY (new_peering_id) REFERENCES peering_history (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'Jumphost 피어링 이전(Migration) 이력';

CREATE INDEX IF NOT EXISTS idx_jmh_status     ON jumphost_migration_history (status);
CREATE INDEX IF NOT EXISTS idx_jmh_created    ON jumphost_migration_history (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_jmh_old_peer   ON jumphost_migration_history (old_peering_id);
CREATE INDEX IF NOT EXISTS idx_jmh_new_peer   ON jumphost_migration_history (new_peering_id);
