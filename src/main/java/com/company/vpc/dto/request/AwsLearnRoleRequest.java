package com.company.vpc.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * AWS 학습용 Role ARN AssumeRole 기반 VPC 목록 조회 요청 DTO.
 *
 * <p>호출자(Caller) IAM 자격증명으로 STS AssumeRole을 수행하여 임시 자격증명을 발급받은 뒤,
 * 해당 권한으로 VPC 목록을 조회한다.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>accessKey + secretKey로 STS 클라이언트 생성</li>
 *   <li>{@code roleArn}으로 AssumeRole 호출 → 임시 자격증명(AccessKeyId + SecretAccessKey + SessionToken) 발급</li>
 *   <li>임시 자격증명으로 EC2 클라이언트 생성 후 DescribeVpcs 호출</li>
 * </ol>
 *
 * <p>자격증명은 서버에 저장되지 않으며 요청 처리 후 즉시 폐기된다.
 */
@Getter
@NoArgsConstructor
public class AwsLearnRoleRequest {

    /** AssumeRole을 호출할 IAM 사용자의 Access Key ID */
    @NotBlank(message = "Access Key는 필수입니다.")
    private String accessKey;

    /** AssumeRole을 호출할 IAM 사용자의 Secret Access Key */
    @NotBlank(message = "Secret Key는 필수입니다.")
    private String secretKey;

    /**
     * AssumeRole 대상 Role ARN (예: arn:aws:iam::123456789012:role/MyRoleName).
     * 형식: {@code arn:aws:iam::<account-id>:role/<role-name>}
     */
    @NotBlank(message = "Role ARN은 필수입니다.")
    @Pattern(
        regexp = "^arn:aws[a-z\\-]*:iam::[0-9]{12}:role/.+$",
        message = "유효한 Role ARN 형식이 아닙니다. (예: arn:aws:iam::123456789012:role/RoleName)"
    )
    private String roleArn;

    /**
     * AWS 리전 코드 (예: ap-northeast-2, us-east-1).
     * VPC 목록 조회에 사용되며, STS 호출은 ap-northeast-2(서울)로 고정된다.
     */
    @NotBlank(message = "Region은 필수입니다.")
    private String region;

    /**
     * AssumeRole 세션 이름 (선택).
     * 미입력 시 "aws-learn-session"이 기본값으로 사용된다.
     */
    private String sessionName;

    /**
     * 역할 위임 시 외부 ID (선택).
     * Trust Policy에 Condition으로 ExternalId가 설정된 역할에 필요하다.
     * 크로스 계정 역할에서 confused deputy 공격을 방지하기 위해 사용한다.
     */
    private String externalId;
}
