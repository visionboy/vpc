package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * VPC 리소스 조회 응답 DTO — EC2 + RDS 인스턴스 목록.
 *
 * <p>VPC ID 조회 시 해당 VPC에 속한 EC2·RDS 인스턴스 전체를 담아 반환한다.
 *
 * <p>RDS 조회 오류(IAM 권한 없음, 네트워크 오류 등)가 발생한 경우:
 * <ul>
 *   <li>{@code rdsInstances}는 빈 리스트</li>
 *   <li>{@code rdsError}에 오류 메시지 설정</li>
 * </ul>
 * 이 방식으로 EC2 결과는 항상 정상 반환되고, RDS 오류는 프론트엔드에서 별도 표시한다.
 */
@Getter
@Builder
public class VpcResourceResponse {

    /** 조회 대상 VPC ID */
    private String vpcId;

    /** VPC 내 EC2 인스턴스 목록 (terminated 제외) */
    private List<Ec2InstanceDto> ec2Instances;

    /** VPC 내 RDS 인스턴스 목록 — rdsError가 있으면 빈 리스트 */
    private List<RdsInstanceDto> rdsInstances;

    /**
     * RDS 조회 오류 메시지 — 정상 조회 시 null.
     * 주요 원인: IAM 사용자에게 rds:DescribeDBInstances 권한 없음.
     */
    private String rdsError;
}
