package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * EC2 인스턴스 정보 응답 DTO.
 *
 * <p>VPC ID 조회 시 해당 VPC에 속한 EC2 인스턴스 목록에 사용된다.
 * terminated 상태 인스턴스는 제외하고 running, stopped, pending, stopping만 포함한다.
 */
@Getter
@Builder
public class Ec2InstanceDto {

    /** EC2 인스턴스 ID (예: i-0abc1234567890def) */
    private String instanceId;

    /** AWS Name 태그 값 — 없으면 빈 문자열 */
    private String name;

    /** 인스턴스 타입 (예: t3.micro, m5.large) */
    private String instanceType;

    /** 인스턴스 상태 (running / stopped / pending / stopping) */
    private String state;

    /** 프라이빗 IP 주소 (예: 10.1.0.5) */
    private String privateIp;

    /** 이 인스턴스에 연결된 보안 그룹 ID 목록 — [상세] 버튼 클릭 시 조회에 사용 */
    private List<String> securityGroupIds;
}
