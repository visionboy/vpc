package com.company.vpc.config;

import com.company.vpc.exception.BusinessException;
import com.company.vpc.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS 클라이언트 동적 생성 팩토리.
 *
 * <p>account 파라미터(계정 이름)를 기반으로 EC2/RDS 클라이언트를 생성하고 캐시한다.
 *
 * <p>자격증명 전략:
 * <ul>
 *   <li>"requester" → IAM 사용자 장기 자격증명 ({@link StaticCredentialsProvider})</li>
 *   <li>그 외 → application.yml의 {@code aws.accounts} 맵에서 Role ARN 조회 후
 *       STS AssumeRole로 임시 자격증명 발급 ({@link StsAssumeRoleCredentialsProvider})</li>
 * </ul>
 *
 * <p>새 계정 추가 방법: application.yml의 {@code aws.accounts}에 항목 추가 후 재시작.
 * 코드 변경 불필요.
 *
 * <p>클라이언트 캐시: 계정당 최초 1회 생성 후 재사용 (StsAssumeRoleCredentialsProvider가 임시 자격증명을 자동 갱신).
 */
@Component
@EnableConfigurationProperties(AwsProperties.class)
@Slf4j
public class AwsClientFactory {

    private final AwsProperties properties;

    /** 요청자 장기 자격증명 — STS 클라이언트 생성 및 requester 클라이언트에 공유 */
    private final AwsCredentialsProvider requesterCredentials;

    /** EC2 클라이언트 캐시 — key: account 이름, value: 생성된 클라이언트 */
    private final ConcurrentHashMap<String, Ec2Client> ec2Cache = new ConcurrentHashMap<>();

    /** RDS 클라이언트 캐시 — key: account 이름, value: 생성된 클라이언트 */
    private final ConcurrentHashMap<String, RdsClient> rdsCache = new ConcurrentHashMap<>();

    public AwsClientFactory(AwsProperties properties) {
        this.properties = properties;
        // requester 자격증명은 한 번만 생성해 STS·requester 클라이언트에 모두 재사용
        AwsProperties.RequesterProperties r = properties.getRequester();
        this.requesterCredentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(r.getAccessKey(), r.getSecretKey()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 퍼블릭 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * account 이름으로 EC2 클라이언트 반환 (캐시 우선).
     *
     * @param account "requester" 또는 application.yml accounts 맵의 키 (예: "accepter", "dev-account")
     * @throws BusinessException 등록되지 않은 account 이름일 때
     */
    public Ec2Client getEc2Client(String account) {
        return ec2Cache.computeIfAbsent(account, this::createEc2Client);
    }

    /**
     * account 이름으로 RDS 클라이언트 반환 (캐시 우선).
     *
     * @param account "requester" 또는 application.yml accounts 맵의 키
     * @throws BusinessException 등록되지 않은 account 이름일 때
     */
    public RdsClient getRdsClient(String account) {
        return rdsCache.computeIfAbsent(account, this::createRdsClient);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 클라이언트 생성 (캐시 미스 시 1회 실행)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * EC2 클라이언트 생성.
     * "requester"는 정적 자격증명, 나머지는 AssumeRole 자격증명을 사용한다.
     */
    private Ec2Client createEc2Client(String account) {
        if ("requester".equalsIgnoreCase(account)) {
            log.debug("EC2 클라이언트 생성 (requester): region={}", properties.getRequester().getRegion());
            return Ec2Client.builder()
                    .region(Region.of(properties.getRequester().getRegion()))
                    .credentialsProvider(requesterCredentials)
                    .build();
        }
        AwsProperties.AccountProperties props = resolveAccountProps(account);
        log.debug("EC2 클라이언트 생성 (AssumeRole): account={}, region={}", account, props.getRegion());
        return Ec2Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(assumeRoleCredentials(props))
                .build();
    }

    /**
     * RDS 클라이언트 생성.
     * "requester"는 정적 자격증명, 나머지는 AssumeRole 자격증명을 사용한다.
     */
    private RdsClient createRdsClient(String account) {
        if ("requester".equalsIgnoreCase(account)) {
            log.debug("RDS 클라이언트 생성 (requester): region={}", properties.getRequester().getRegion());
            return RdsClient.builder()
                    .region(Region.of(properties.getRequester().getRegion()))
                    .credentialsProvider(requesterCredentials)
                    .build();
        }
        AwsProperties.AccountProperties props = resolveAccountProps(account);
        log.debug("RDS 클라이언트 생성 (AssumeRole): account={}, region={}", account, props.getRegion());
        return RdsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(assumeRoleCredentials(props))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * accounts 맵에서 account 이름으로 설정 조회.
     * 등록되지 않은 이름이면 즉시 예외를 던진다.
     *
     * @param account application.yml accounts 맵의 키
     * @throws BusinessException 해당 account가 maps에 없을 때
     */
    private AwsProperties.AccountProperties resolveAccountProps(String account) {
        AwsProperties.AccountProperties props = properties.getAccounts().get(account);
        if (props == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_CSP,
                    String.format("등록되지 않은 계정: '%s'. application.yml의 aws.accounts에 추가하세요.", account));
        }
        return props;
    }

    /**
     * STS AssumeRole 기반 임시 자격증명 공급자 생성.
     *
     * <p>requester 자격증명으로 STS를 호출해 임시 자격증명(TTL 1시간)을 발급받는다.
     * {@link StsAssumeRoleCredentialsProvider}는 만료 전 자동 갱신하므로 애플리케이션 재시작이 불필요하다.
     *
     * @param props 대상 계정의 Role ARN·세션 이름 설정
     */
    private AwsCredentialsProvider assumeRoleCredentials(AwsProperties.AccountProperties props) {
        // requester 자격증명으로 STS 클라이언트 생성 — AssumeRole 호출 전용
        StsClient stsClient = StsClient.builder()
                .region(Region.of(properties.getRequester().getRegion()))
                .credentialsProvider(requesterCredentials)
                .build();

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(r -> r
                        .roleArn(props.getRoleArn())
                        .roleSessionName(props.getRoleSessionName()))
                .build();
    }
}
