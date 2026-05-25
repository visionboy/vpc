package com.company.vpc.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AWS 설정 클래스.
 *
 * <p>AWS 클라이언트는 {@link AwsClientFactory}가 account 이름 기반으로 동적 생성·캐시하므로
 * 이 클래스에서는 고정 Bean을 선언하지 않는다.
 *
 * <p>계정 추가 방법 (코드 변경 불필요):
 * <pre>
 * # application.yml
 * aws:
 *   accounts:
 *     accepter:                                   # 기존 계정
 *       role-arn: arn:aws:iam::999999999999:role/VpcPeeringRole
 *       region: ap-northeast-2
 *     new-account:                                # 추가할 계정 — 이 항목만 추가하면 됨
 *       role-arn: arn:aws:iam::111122223333:role/VpcPeeringRole
 *       region: ap-northeast-2
 * </pre>
 *
 * <p>클라이언트 사용 방법: {@link AwsClientFactory#getEc2Client(String)} / {@link AwsClientFactory#getRdsClient(String)}
 * <br>자세한 설명: {@code docs/aws-cross-account-setup.md}
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {
    // AwsClientFactory가 모든 클라이언트 생성·캐싱을 담당한다.
}
