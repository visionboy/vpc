package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Vpc;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AWS VPC 단건 정보 응답 DTO.
 *
 * <p>AWS SDK {@link Vpc} 모델을 프론트엔드 표시용으로 변환한다.
 * Name 태그는 별도 필드로 분리하여 화면에서 편리하게 사용할 수 있도록 한다.
 */
@Getter
@Builder
public class VpcInfoDto {

    /** AWS VPC ID (예: vpc-0abc1234def567890) */
    private String vpcId;

    /** VPC Name 태그 값 — 설정되지 않으면 빈 문자열 */
    private String name;

    /** VPC 기본 IPv4 CIDR 블록 (예: 10.0.0.0/16) */
    private String cidrBlock;

    /** VPC 상태 — "available" 또는 "pending" */
    private String state;

    /** AWS 기본 VPC 여부 — 계정 생성 시 자동으로 만들어지는 VPC */
    private boolean isDefault;

    /** VPC 소유자 AWS 계정 ID (12자리 숫자) */
    private String ownerId;

    /** VPC에 부착된 태그 목록 (Name 태그 포함) */
    private List<TagDto> tags;

    // ─────────────────────────────────────────────────────────────────────────
    // 팩토리 메서드
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AWS SDK {@link Vpc} 객체를 {@link VpcInfoDto}로 변환한다.
     *
     * @param vpc AWS SDK VPC 모델
     * @return 변환된 VpcInfoDto
     */
    public static VpcInfoDto from(Vpc vpc) {
        String name = vpc.tags().stream()
                .filter(t -> "Name".equals(t.key()))
                .map(Tag::value)
                .findFirst()
                .orElse("");

        List<TagDto> tags = vpc.tags().stream()
                .map(t -> TagDto.builder().key(t.key()).value(t.value()).build())
                .collect(Collectors.toList());

        return VpcInfoDto.builder()
                .vpcId(vpc.vpcId())
                .name(name)
                .cidrBlock(vpc.cidrBlock())
                .state(vpc.stateAsString())
                .isDefault(Boolean.TRUE.equals(vpc.isDefault()))
                .ownerId(vpc.ownerId())
                .tags(tags)
                .build();
    }

    /**
     * VPC 태그 키-값 쌍 DTO.
     */
    @Getter
    @Builder
    public static class TagDto {
        /** 태그 키 (예: "Name", "Environment") */
        private String key;
        /** 태그 값 (예: "prod-vpc", "production") */
        private String value;
    }
}
