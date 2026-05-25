package com.company.vpc.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * application.yml의 {@code aws} 프리픽스 설정을 바인딩하는 설정 프로퍼티 클래스.
 *
 * <pre>
 * aws:
 *   requester:                          # 요청자 계정 (장기 자격증명)
 *     access-key: ...
 *     secret-key: ...
 *     region: ap-northeast-2
 *
 *   accounts:                           # accepter 계정 목록 (AssumeRole, 계정 추가 시 여기에만 추가)
 *     accepter:                         # account 파라미터에서 사용할 이름 (자유롭게 지정)
 *       role-arn: arn:aws:iam::999999999999:role/VpcPeeringRole
 *       region: ap-northeast-2
 *     dev-account:                      # 추가 계정 예시
 *       role-arn: arn:aws:iam::111122223333:role/VpcPeeringRole
 *       region: ap-southeast-1
 * </pre>
 *
 * <p>새 계정 추가 시 코드 변경 없이 {@code accounts} 맵에 항목만 추가하면 된다.
 * 설정 방법: {@code docs/aws-cross-account-setup.md} 참고.
 */
@ConfigurationProperties(prefix = "aws")
@Getter
@Setter
public class AwsProperties {

    /** 요청자 계정 — IAM 사용자 장기 자격증명으로 직접 인증 */
    private RequesterProperties requester = new RequesterProperties();

    /**
     * accepter 계정 목록 — key: account 파라미터명, value: IAM Role 설정.
     * 새 계정 추가 시 이 맵에 항목만 추가하면 코드 변경 없이 즉시 사용 가능.
     */
    private Map<String, AccountProperties> accounts = new LinkedHashMap<>();

    /**
     * 요청자(Requester) AWS 계정 자격증명.
     * IAM 사용자의 장기 자격증명을 직접 보관한다.
     * 운영 환경에서는 환경변수 또는 AWS Secrets Manager로 주입 권장.
     */
    @Getter
    @Setter
    public static class RequesterProperties {

        /** AWS IAM Access Key ID */
        private String accessKey;

        /** AWS IAM Secret Access Key — 절대 코드·로그에 노출 금지 */
        private String secretKey;

        /** AWS 리전 (예: ap-northeast-2) */
        private String region;
    }

    /**
     * accepter 계정 IAM Role 설정.
     * access-key/secret-key 없이 AssumeRole로 임시 자격증명(TTL 1시간, 자동 갱신)을 발급받는다.
     */
    @Getter
    @Setter
    public static class AccountProperties {

        /**
         * 위임받을 IAM Role ARN.
         * 형식: {@code arn:aws:iam::ACCOUNT_ID:role/ROLE_NAME}
         */
        private String roleArn;

        /** AWS 리전 (예: ap-northeast-2) */
        private String region;

        /**
         * STS AssumeRole 세션 이름 — CloudTrail 로그에서 호출 출처를 식별하는 데 사용.
         * 기본값: "vpc-peering-session"
         */
        private String roleSessionName = "vpc-peering-session";
    }
}
