package com.company.vpc.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;

/**
 * AWS 학습용 VPC 목록 조회 요청 DTO.
 *
 * <p>사용자가 직접 입력한 AWS 자격증명과 리전으로 임시 EC2 클라이언트를 생성하여 VPC 목록을 조회한다.
 * 자격증명은 서버에 저장되지 않으며 요청 처리 후 즉시 폐기된다.
 *
 * <p>학습·테스트 목적 전용 — 운영 환경에서 자격증명을 직접 전달하는 방식은 사용하지 않는다.
 */
@Getter
@NoArgsConstructor
public class AwsLearnVpcRequest {

    /** AWS IAM 사용자 Access Key ID (예: AKIAIOSFODNN7EXAMPLE) */
    @NotBlank(message = "Access Key는 필수입니다.")
    private String accessKey;

    /** AWS IAM 사용자 Secret Access Key */
    @NotBlank(message = "Secret Key는 필수입니다.")
    private String secretKey;

    /**
     * AWS 리전 코드 (예: ap-northeast-2, us-east-1).
     * 리전에 따라 조회되는 VPC 목록이 달라진다.
     */
    @NotBlank(message = "Region은 필수입니다.")
    private String region;
}
