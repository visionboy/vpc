package com.company.vpc.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 마이그레이션 전략 설정.
 *
 * <p>migrate() 전에 repair()를 먼저 실행한다.
 * 실패한 마이그레이션 기록이 flyway_schema_history에 남아 있을 때
 * (예: 테이블 이미 존재 등) 서버 기동이 차단되는 문제를 방지한다.
 */
@Configuration
public class FlywayConfig {

    /**
     * repair → migrate 순서로 실행하는 전략 Bean.
     * repair()는 실패 기록을 제거하고 체크섬을 재계산한다.
     */
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
