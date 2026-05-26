package com.company.vpc.service.impl;

import com.company.vpc.dto.request.AwsLearnRoleRequest;
import com.company.vpc.dto.request.AwsLearnVpcRequest;
import com.company.vpc.dto.response.AwsLearnRoleResourceResponse;
import com.company.vpc.dto.response.AwsLearnRoleResourceResponse.LearnEc2Dto;
import com.company.vpc.dto.response.AwsLearnRoleResourceResponse.LearnRdsDto;
import com.company.vpc.dto.response.VpcInfoDto;
import com.company.vpc.exception.BusinessException;
import com.company.vpc.exception.ErrorCode;
import com.company.vpc.service.AwsLearnService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AWS 학습용 VPC 조회 서비스 구현체.
 *
 * <p>요청마다 임시 클라이언트를 생성하여 VPC 목록을 조회하고 즉시 닫는다.
 * 자격증명은 메모리에만 유지되며 요청 처리 후 GC에 의해 회수된다.
 *
 * <p>두 가지 방식을 지원한다:
 * <ul>
 *   <li>{@link #listVpcs} — Access Key + Secret Key로 직접 EC2 클라이언트 생성</li>
 *   <li>{@link #listVpcsByRole} — STS AssumeRole로 임시 자격증명 발급 후 EC2 조회</li>
 * </ul>
 *
 * <p>주의: 요청마다 AWS 클라이언트를 새로 생성하므로 {@link com.company.vpc.config.AwsClientFactory}
 * 캐시를 사용하지 않는다. 학습 화면 특성상 호출 빈도가 낮아 성능 문제는 없다.
 */
@Service
@Slf4j
public class AwsLearnServiceImpl implements AwsLearnService {

    /** AssumeRole 기본 세션 이름 — 요청에 sessionName이 없을 때 사용 */
    private static final String DEFAULT_SESSION_NAME = "aws-learn-session";

    // ─────────────────────────────────────────────────────────────────────────
    // 직접 자격증명 방식
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>입력된 자격증명으로 {@link StaticCredentialsProvider} 생성</li>
     *   <li>지정된 리전에 대한 임시 {@link Ec2Client} 생성</li>
     *   <li>DescribeVpcs API 호출 — 필터 없이 전체 VPC 조회</li>
     *   <li>응답을 {@link VpcInfoDto} 목록으로 변환 후 클라이언트 닫기</li>
     * </ol>
     */
    @Override
    public List<VpcInfoDto> listVpcs(AwsLearnVpcRequest request) {
        log.info("AWS 학습 VPC 조회 시작 (직접 자격증명): region={}", request.getRegion());

        // try-with-resources로 임시 클라이언트를 요청 처리 후 즉시 닫는다
        try (Ec2Client ec2 = buildEc2Client(request)) {
            return describeVpcs(ec2, request.getRegion());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AWS VPC 조회 실패 (직접 자격증명): region={}, error={}", request.getRegion(), e.getMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR, "VPC 조회 실패: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Role ARN (STS AssumeRole) 방식
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>호출자 자격증명(accessKey + secretKey)으로 STS 클라이언트 생성</li>
     *   <li>STS AssumeRole 호출 → 임시 자격증명(AccessKeyId + SecretAccessKey + SessionToken) 발급</li>
     *   <li>임시 자격증명으로 EC2 클라이언트 생성</li>
     *   <li>DescribeVpcs 호출 후 클라이언트 닫기</li>
     * </ol>
     */
    @Override
    public List<VpcInfoDto> listVpcsByRole(AwsLearnRoleRequest request) {
        log.info("AWS 학습 VPC 조회 시작 (Role ARN): roleArn={}, region={}",
                request.getRoleArn(), request.getRegion());

        // 1단계: 호출자 자격증명으로 STS 클라이언트 생성 후 AssumeRole
        Credentials tempCreds = assumeRole(request);

        // 2단계: 발급된 임시 자격증명으로 EC2 클라이언트 생성 후 VPC 조회
        try (Ec2Client ec2 = buildEc2ClientFromSession(tempCreds, request.getRegion())) {
            return describeVpcs(ec2, request.getRegion());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AWS VPC 조회 실패 (Role ARN): region={}, error={}", request.getRegion(), e.getMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR, "VPC 조회 실패: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Role ARN — VPC + EC2 + RDS 통합 조회
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>AssumeRole로 임시 자격증명 발급</li>
     *   <li>EC2 클라이언트로 VPC 전체 조회</li>
     *   <li>EC2 클라이언트로 전체 인스턴스 조회 (terminated 제외)</li>
     *   <li>RDS 클라이언트로 전체 DB 인스턴스 조회 (실패 시 rdsError에 담고 계속)</li>
     * </ol>
     */
    @Override
    public AwsLearnRoleResourceResponse getResourcesByRole(AwsLearnRoleRequest request) {
        log.info("AWS 학습 리소스 통합 조회 시작 (Role ARN): roleArn={}, region={}",
                request.getRoleArn(), request.getRegion());

        Credentials tempCreds = assumeRole(request);
        StaticCredentialsProvider sessionProvider = buildSessionProvider(tempCreds);

        // EC2 클라이언트로 VPC + EC2 조회
        List<VpcInfoDto> vpcs;
        List<LearnEc2Dto> ec2Instances;
        try (Ec2Client ec2 = buildClientWithProvider(sessionProvider, request.getRegion())) {
            vpcs = describeVpcs(ec2, request.getRegion());
            ec2Instances = describeAllEc2Instances(ec2, request.getRegion());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("EC2 리소스 조회 실패: region={}, error={}", request.getRegion(), e.getMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR, "리소스 조회 실패: " + e.getMessage());
        }

        // RDS 조회 — 실패해도 EC2/VPC 결과는 유지
        List<LearnRdsDto> rdsInstances = Collections.emptyList();
        String rdsError = null;
        try (RdsClient rds = RdsClient.builder()
                .region(Region.of(request.getRegion()))
                .credentialsProvider(sessionProvider)
                .build()) {
            rdsInstances = describeAllRdsInstances(rds, request.getRegion());
        } catch (Exception e) {
            log.warn("RDS 리소스 조회 실패 (권한 없음 등): region={}, error={}", request.getRegion(), e.getMessage());
            rdsError = e.getMessage();
        }

        log.info("AWS 학습 리소스 통합 조회 완료: region={}, vpc={}, ec2={}, rds={}",
                request.getRegion(), vpcs.size(), ec2Instances.size(), rdsInstances.size());

        return AwsLearnRoleResourceResponse.builder()
                .vpcs(vpcs)
                .ec2Instances(ec2Instances)
                .rdsInstances(rdsInstances)
                .rdsError(rdsError)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * EC2 DescribeVpcs를 호출하여 {@link VpcInfoDto} 목록으로 변환한다.
     * 직접 자격증명 방식·Role ARN 방식 모두 이 메서드를 공유한다.
     *
     * @param ec2    조회에 사용할 EC2 클라이언트
     * @param region 로그에 출력할 리전 문자열
     * @return VPC 목록 (없으면 빈 리스트)
     * @throws BusinessException EC2 API 오류 시
     */
    private List<VpcInfoDto> describeVpcs(Ec2Client ec2, String region) {
        try {
            DescribeVpcsResponse response = ec2.describeVpcs();
            List<VpcInfoDto> vpcs = response.vpcs().stream()
                    .map(VpcInfoDto::from)
                    .collect(Collectors.toList());

            log.info("AWS 학습 VPC 조회 완료: region={}, count={}", region, vpcs.size());
            return vpcs;

        } catch (Ec2Exception e) {
            log.warn("EC2 DescribeVpcs 실패: region={}, code={}, error={}",
                    region, e.awsErrorDetails().errorCode(), e.awsErrorDetails().errorMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR,
                    "VPC 조회 실패: " + e.awsErrorDetails().errorMessage());
        }
    }

    /**
     * STS AssumeRole을 수행하여 임시 자격증명을 발급받는다.
     *
     * <p>STS 클라이언트는 호출자의 자격증명으로 생성된다.
     * STS 엔드포인트는 글로벌이므로 리전은 ap-northeast-2(서울)로 고정한다.
     *
     * @param request roleArn, sessionName, externalId가 담긴 요청 DTO
     * @return AWS 임시 자격증명 (AccessKeyId + SecretAccessKey + SessionToken, TTL 1시간)
     * @throws BusinessException AssumeRole 권한 없음 또는 STS API 오류 시
     */
    private Credentials assumeRole(AwsLearnRoleRequest request) {
        StaticCredentialsProvider callerCreds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(request.getAccessKey(), request.getSecretKey()));

        // STS는 글로벌 서비스이나 리전 엔드포인트를 사용하도록 서울로 고정
        try (StsClient sts = StsClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(callerCreds)
                .build()) {

            String sessionName = StringUtils.hasText(request.getSessionName())
                    ? request.getSessionName()
                    : DEFAULT_SESSION_NAME;

            AssumeRoleRequest.Builder builder = AssumeRoleRequest.builder()
                    .roleArn(request.getRoleArn())
                    .roleSessionName(sessionName);

            // ExternalId가 설정된 경우에만 포함 — Trust Policy에 Condition이 있을 때 필요
            if (StringUtils.hasText(request.getExternalId())) {
                builder.externalId(request.getExternalId());
            }

            AssumeRoleResponse response = sts.assumeRole(builder.build());
            log.info("STS AssumeRole 성공: roleArn={}, sessionName={}", request.getRoleArn(), sessionName);
            return response.credentials();

        } catch (StsException e) {
            log.warn("STS AssumeRole 실패: roleArn={}, code={}, error={}",
                    request.getRoleArn(), e.awsErrorDetails().errorCode(), e.awsErrorDetails().errorMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR,
                    "Role 권한 획득 실패: " + e.awsErrorDetails().errorMessage());
        }
    }

    /**
     * 직접 자격증명(Access Key + Secret Key)으로 EC2 클라이언트를 생성한다.
     * 호출자가 try-with-resources로 닫아야 한다.
     *
     * @param request accessKey, secretKey, region이 담긴 요청 DTO
     * @return 생성된 임시 EC2 클라이언트
     */
    private Ec2Client buildEc2Client(AwsLearnVpcRequest request) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(request.getAccessKey(), request.getSecretKey()));

        return Ec2Client.builder()
                .region(Region.of(request.getRegion()))
                .credentialsProvider(credentials)
                .build();
    }

    /**
     * STS 임시 자격증명(AccessKeyId + SecretAccessKey + SessionToken)으로 EC2 클라이언트를 생성한다.
     * SessionToken이 포함된 {@link AwsSessionCredentials}를 사용해야 임시 자격증명이 정상 작동한다.
     *
     * @param creds  STS AssumeRole로 발급된 임시 자격증명
     * @param region 조회할 AWS 리전 코드
     * @return 생성된 임시 EC2 클라이언트
     */
    private Ec2Client buildEc2ClientFromSession(Credentials creds, String region) {
        return buildClientWithProvider(buildSessionProvider(creds), region);
    }

    /**
     * STS 임시 자격증명으로 {@link StaticCredentialsProvider}를 생성한다.
     * EC2·RDS 클라이언트에서 공용으로 재사용한다.
     *
     * <p>일반 {@link AwsBasicCredentials}가 아닌 {@link AwsSessionCredentials}를 사용해야
     * SessionToken이 포함되어 임시 자격증명이 정상 작동한다.
     *
     * @param creds STS AssumeRole로 발급된 임시 자격증명
     * @return SessionToken이 포함된 StaticCredentialsProvider
     */
    private StaticCredentialsProvider buildSessionProvider(Credentials creds) {
        return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(
                        creds.accessKeyId(),
                        creds.secretAccessKey(),
                        creds.sessionToken()));
    }

    /**
     * 지정된 자격증명 공급자로 EC2 클라이언트를 생성한다.
     * 호출자가 try-with-resources로 닫아야 한다.
     *
     * @param provider 자격증명 공급자 (직접 자격증명 또는 임시 세션 자격증명)
     * @param region   조회할 AWS 리전 코드
     * @return 생성된 EC2 클라이언트
     */
    private Ec2Client buildClientWithProvider(StaticCredentialsProvider provider, String region) {
        return Ec2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(provider)
                .build();
    }

    /**
     * 리전 내 전체 EC2 인스턴스를 조회한다 (terminated 상태 제외).
     *
     * <p>VPC 필터 없이 전체 인스턴스를 조회하여 {@link LearnEc2Dto}로 변환한다.
     *
     * @param ec2    조회에 사용할 EC2 클라이언트
     * @param region 로그에 출력할 리전 문자열
     * @return 전체 EC2 인스턴스 목록 (없으면 빈 리스트)
     */
    private List<LearnEc2Dto> describeAllEc2Instances(Ec2Client ec2, String region) {
        try {
            // terminated 상태만 제외 — 그 외(running, stopped, pending, stopping) 모두 포함
            var response = ec2.describeInstances(DescribeInstancesRequest.builder()
                    .filters(Filter.builder()
                            .name("instance-state-name")
                            .values("running", "stopped", "pending", "stopping")
                            .build())
                    .build());

            List<LearnEc2Dto> instances = response.reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .map(LearnEc2Dto::from)
                    .collect(Collectors.toList());

            log.debug("EC2 인스턴스 조회 완료: region={}, count={}", region, instances.size());
            return instances;

        } catch (Ec2Exception e) {
            log.warn("EC2 DescribeInstances 실패: region={}, error={}", region, e.awsErrorDetails().errorMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR,
                    "EC2 인스턴스 조회 실패: " + e.awsErrorDetails().errorMessage());
        }
    }

    /**
     * 리전 내 전체 RDS DB 인스턴스를 페이지 순회하여 조회한다.
     *
     * <p>RDS API는 최대 100개씩 반환하므로 marker가 없어질 때까지 반복 호출한다.
     *
     * @param rds    조회에 사용할 RDS 클라이언트
     * @param region 로그에 출력할 리전 문자열
     * @return 전체 RDS 인스턴스 목록 (없으면 빈 리스트)
     */
    private List<LearnRdsDto> describeAllRdsInstances(RdsClient rds, String region) {
        List<DBInstance> all = new ArrayList<>();
        String marker = null;

        // RDS API는 marker 기반 페이지네이션 — marker가 없어질 때까지 반복
        do {
            DescribeDbInstancesRequest.Builder req = DescribeDbInstancesRequest.builder().maxRecords(100);
            if (marker != null) req.marker(marker);

            DescribeDbInstancesResponse res = rds.describeDBInstances(req.build());
            all.addAll(res.dbInstances());
            marker = res.marker();
        } while (marker != null && !marker.isEmpty());

        log.debug("RDS 인스턴스 조회 완료: region={}, count={}", region, all.size());

        return all.stream()
                .map(LearnRdsDto::from)
                .collect(Collectors.toList());
    }
}
