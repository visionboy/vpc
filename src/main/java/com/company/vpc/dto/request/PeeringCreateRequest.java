package com.company.vpc.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * VPC Peering 생성 요청 DTO.
 *
 * <p>Bean Validation 어노테이션으로 입력값을 검증한다.
 * 검증 실패 시 {@link com.company.vpc.exception.GlobalExceptionHandler}가 400으로 처리한다.
 *
 * <p>필드 구성:
 * <ul>
 *   <li>요청자(Requester): VPC ID, CIDR, 계정 ID, 라우팅 테이블 ID</li>
 *   <li>수락자(Accepter): VPC ID, CIDR, 계정 ID, 라우팅 테이블 ID, 보안 그룹 ID(선택)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeeringCreateRequest {

    /** 사용자 정의 Peering 이름 — 필수 아니지만 100자 제한 */
    @Size(max = 100, message = "피어링 이름은 100자 이하로 입력해주세요.")
    private String peeringName;

    // ── 요청자(Requester) 정보 ──────────────────────────────────────────────

    /** 요청자 VPC ID (예: vpc-0abc1234567890def) */
    @NotBlank(message = "요청자 VPC ID는 필수입니다.")
    private String requesterVpcId;

    /** 요청자 VPC CIDR — IPv4 CIDR 형식 검증 (예: 10.1.0.0/16) */
    @NotBlank(message = "요청자 CIDR은 필수입니다.")
    @Pattern(regexp = "^([0-9]{1,3}\\.){3}[0-9]{1,3}/[0-9]{1,2}$",
             message = "유효한 CIDR 형식이 아닙니다. (예: 10.1.0.0/16)")
    private String requesterCidr;

    /** 요청자 AWS 계정 ID (크로스 계정 Peering 시 필요, 선택) */
    private String requesterAccountId;

    /** 요청자 라우팅 테이블 ID — 수락자 CIDR 경로가 추가될 테이블 */
    @NotBlank(message = "요청자 라우팅 테이블 ID는 필수입니다.")
    private String requesterRouteTableId;

    // ── 수락자(Accepter) 정보 ──────────────────────────────────────────────

    /** 수락자 VPC ID */
    @NotBlank(message = "수락자 VPC ID는 필수입니다.")
    private String accepterVpcId;

    /** 수락자 VPC CIDR — 요청자 CIDR과 겹치면 안 됨 */
    @NotBlank(message = "수락자 CIDR은 필수입니다.")
    @Pattern(regexp = "^([0-9]{1,3}\\.){3}[0-9]{1,3}/[0-9]{1,2}$",
             message = "유효한 CIDR 형식이 아닙니다. (예: 10.2.0.0/16)")
    private String accepterCidr;

    /** 수락자 AWS 계정 ID (선택) */
    private String accepterAccountId;

    /** 수락자 라우팅 테이블 ID — 요청자 CIDR 경로가 추가될 테이블 */
    @NotBlank(message = "수락자 라우팅 테이블 ID는 필수입니다.")
    private String accepterRouteTableId;

    /** 수락자 보안 그룹 ID — 지정 시 ICMP 인바운드 규칙이 자동 추가됨 (선택) */
    private String accepterSecurityGroupId;
}
