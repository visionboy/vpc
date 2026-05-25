-- VPC Peering 이력 테이블
CREATE TABLE peering_history
(
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    peering_connection_id     VARCHAR(50)  NULL     COMMENT 'AWS pcx-xxxx 식별자',
    peering_name              VARCHAR(100) NULL,
    csp_type                  VARCHAR(20)  NOT NULL DEFAULT 'AWS',
    requester_vpc_id          VARCHAR(50)  NOT NULL,
    requester_cidr            VARCHAR(20)  NOT NULL,
    requester_account_id      VARCHAR(50)  NULL,
    requester_route_table_id  VARCHAR(50)  NULL,
    accepter_vpc_id           VARCHAR(50)  NOT NULL,
    accepter_cidr             VARCHAR(20)  NOT NULL,
    accepter_account_id       VARCHAR(50)  NULL,
    accepter_route_table_id   VARCHAR(50)  NULL,
    accepter_security_group_id VARCHAR(50) NULL,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at                DATETIME     NOT NULL,
    updated_at                DATETIME     NOT NULL,
    deleted_at                DATETIME     NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'VPC Peering 생성/삭제 이력';

-- 네트워크 스냅샷 테이블 (삭제 직전 상태 JSON 백업)
CREATE TABLE network_snapshot
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    peering_history_id  BIGINT       NOT NULL,
    data_type           VARCHAR(30)  NOT NULL COMMENT 'ROUTE_TABLE | SECURITY_GROUP',
    snapshot_data       LONGTEXT     NOT NULL COMMENT '원본 리소스 상태 JSON',
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    CONSTRAINT fk_snapshot_history
        FOREIGN KEY (peering_history_id) REFERENCES peering_history (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '삭제 전 네트워크 자원 스냅샷';

-- 인덱스
CREATE INDEX idx_ph_status ON peering_history (status);
CREATE INDEX idx_ph_connection_id ON peering_history (peering_connection_id);
CREATE INDEX idx_ns_history_id ON network_snapshot (peering_history_id);
