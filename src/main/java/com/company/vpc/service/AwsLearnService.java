package com.company.vpc.service;

import com.company.vpc.dto.request.AwsLearnRoleRequest;
import com.company.vpc.dto.request.AwsLearnVpcRequest;
import com.company.vpc.dto.response.AwsLearnRoleResourceResponse;
import com.company.vpc.dto.response.VpcInfoDto;

import java.util.List;

/**
 * AWS 학습용 VPC 조회 서비스 인터페이스.
 *
 * <p>두 가지 자격증명 방식을 지원한다:
 * <ul>
 *   <li>직접 자격증명 — Access Key + Secret Key로 EC2 클라이언트 직접 생성</li>
 *   <li>Role ARN — STS AssumeRole로 임시 자격증명을 발급받아 EC2 클라이언트 생성</li>
 * </ul>
 *
 * <p>학습·테스트 목적 전용 — 자격증명은 서버에 저장하지 않는다.
 *
 * @see com.company.vpc.service.impl.AwsLearnServiceImpl
 */
public interface AwsLearnService {

    /**
     * 직접 입력된 AWS 자격증명으로 VPC 목록을 조회한다.
     *
     * @param request accessKey, secretKey, region이 담긴 요청 DTO
     * @return 해당 리전의 VPC 목록 (없으면 빈 리스트)
     * @throws com.company.vpc.exception.BusinessException AWS API 인증 오류 또는 접근 거부 시
     */
    List<VpcInfoDto> listVpcs(AwsLearnVpcRequest request);

    /**
     * STS AssumeRole로 임시 자격증명을 발급받아 VPC 목록을 조회한다.
     *
     * <p>호출자의 accessKey + secretKey로 STS AssumeRole을 수행하고,
     * 발급된 임시 자격증명(AccessKeyId + SecretAccessKey + SessionToken)으로
     * EC2 DescribeVpcs를 호출한다.
     *
     * @param request accessKey, secretKey, roleArn, region (+ 선택: sessionName, externalId)
     * @return 해당 리전의 VPC 목록 (없으면 빈 리스트)
     * @throws com.company.vpc.exception.BusinessException AssumeRole 실패 또는 EC2 조회 오류 시
     */
    List<VpcInfoDto> listVpcsByRole(AwsLearnRoleRequest request);

    /**
     * STS AssumeRole로 임시 자격증명을 발급받아 리전 내 전체 리소스(VPC + EC2 + RDS)를 조회한다.
     *
     * <p>EC2는 terminated 상태를 제외한 전체 인스턴스를 조회한다.
     * RDS 조회 실패(IAM 권한 없음 등) 시 응답의 {@code rdsError}에 메시지를 담고
     * VPC·EC2 결과는 정상 반환한다.
     *
     * @param request accessKey, secretKey, roleArn, region (+ 선택: sessionName, externalId)
     * @return 리전 내 VPC + EC2 + RDS 통합 응답
     * @throws com.company.vpc.exception.BusinessException AssumeRole 실패 또는 EC2 조회 오류 시
     */
    AwsLearnRoleResourceResponse getResourcesByRole(AwsLearnRoleRequest request);
}
