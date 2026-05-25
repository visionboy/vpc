package com.company.vpc;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VPC Peering 자동화 시스템의 Spring Boot 애플리케이션 진입점.
 *
 * <p>주요 설정:
 * <ul>
 *   <li>{@code @MapperScan} — com.company.vpc.mapper 패키지의 MyBatis Mapper 인터페이스를 자동 스캔·등록</li>
 *   <li>JPA Auditing 미사용 — MyBatis 전환 후 createdAt/updatedAt은 서비스 계층에서 직접 설정</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan("com.company.vpc.mapper")
public class VpcApplication {

    public static void main(String[] args) {
        SpringApplication.run(VpcApplication.class, args);
    }
}
