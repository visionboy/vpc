package com.company.vpc.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * Jumphost 이전 요청 DTO.
 *
 * <p>기존 Jumphost 피어링(VPC-A ↔ VPC-B)을 신규 Jumphost(VPC-C ↔ VPC-B)로 교체할 때 사용한다.
 * 기존 피어링 ID는 URL 경로 변수({@code /{id}/migrate})로 전달하고,
 * 이 DTO는 신규 Jumphost(요청자, VPC-C) 정보만 담는다.
 *
 * <p>수락자(VPC-B) 정보는 기존 피어링 이력에서 그대로 가져오므로 별도 입력 불필요.
 */
@Getter
@NoArgsConstructor
public class PeeringMigrationRequest {

    /**
     * 신규 피어링 이름 — null이면 "migrated-{기존이름}" 으로 자동 설정.
     */
    @Size(max = 100, message = "피어링 이름은 100자 이하로 입력해주세요.")
    private String newPeeringName;

    /** 신규 Jumphost VPC ID (VPC-C) */
    @NotBlank(message = "신규 요청자 VPC ID는 필수입니다.")
    private String newRequesterVpcId;

    /** 신규 Jumphost CIDR — 수락자(VPC-B) CIDR과 겹치면 안 됨 */
    @NotBlank(message = "신규 요청자 CIDR은 필수입니다.")
    @Pattern(regexp = "^([0-9]{1,3}\\.){3}[0-9]{1,3}/[0-9]{1,2}$",
             message = "유효한 CIDR 형식이 아닙니다. (예: 10.3.0.0/16)")
    private String newRequesterCidr;

    /** 신규 Jumphost 라우팅 테이블 ID — 수락자 CIDR 경로가 추가될 테이블 */
    @NotBlank(message = "신규 요청자 라우팅 테이블 ID는 필수입니다.")
    private String newRequesterRouteTableId;

    /**
     * 신규 요청자 AWS 계정 ID — 크로스 계정 Peering 시만 사용.
     * 동일 계정이면 빈 값으로 둔다.
     */
    private String newRequesterAccountId;

    /**
     * 신규 요청자 계정 설정 키 (application.yml {@code aws.accounts} 맵 키).
     * 기본값 "requester" — 동일 계정이면 입력 불필요.
     */
    private String newRequesterAccount;
}
