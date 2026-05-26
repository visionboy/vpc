package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;
import software.amazon.awssdk.services.ec2.model.GroupIdentifier;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AWS 학습용 Role ARN 기반 리소스 조회 응답 DTO.
 *
 * <p>AssumeRole로 획득한 권한으로 조회한 리전 내 모든 리소스를 담는다.
 * 기존 {@link Ec2InstanceDto}, {@link RdsInstanceDto}는 vpcId를 포함하지 않으므로
 * 학습 화면 전용 내부 DTO를 사용한다.
 *
 * <p>RDS 조회 실패(IAM 권한 없음 등) 시 {@code rdsError}에 오류 메시지를 담고
 * EC2·VPC 결과는 정상 반환한다.
 */
@Getter
@Builder
public class AwsLearnRoleResourceResponse {

    /** 리전 내 전체 VPC 목록 */
    private List<VpcInfoDto> vpcs;

    /** 리전 내 전체 EC2 인스턴스 목록 (terminated 제외) */
    private List<LearnEc2Dto> ec2Instances;

    /** 리전 내 전체 RDS 인스턴스 목록 */
    private List<LearnRdsDto> rdsInstances;

    /**
     * RDS 조회 실패 시 오류 메시지.
     * 정상 조회 시 null. EC2·VPC 결과에는 영향을 주지 않는다.
     */
    private String rdsError;

    // ─────────────────────────────────────────────────────────────────────────
    // EC2 전용 DTO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 학습 화면 전용 EC2 인스턴스 DTO.
     * 기존 {@link Ec2InstanceDto}에 없는 {@code vpcId}, {@code publicIp}, {@code availabilityZone}을 포함한다.
     */
    @Getter
    @Builder
    public static class LearnEc2Dto {

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

        /** 퍼블릭 IP 주소 — 없으면 빈 문자열 */
        private String publicIp;

        /** 이 인스턴스가 속한 VPC ID */
        private String vpcId;

        /** 이 인스턴스가 속한 서브넷 ID */
        private String subnetId;

        /** 가용 영역 (예: ap-northeast-2a) */
        private String availabilityZone;

        /** 연결된 보안 그룹 ID 목록 */
        private List<String> securityGroupIds;

        /**
         * AWS SDK {@link Instance}를 {@link LearnEc2Dto}로 변환한다.
         *
         * @param inst AWS SDK EC2 인스턴스 모델
         * @return 변환된 LearnEc2Dto
         */
        public static LearnEc2Dto from(Instance inst) {
            String name = inst.tags().stream()
                    .filter(t -> "Name".equals(t.key()))
                    .map(Tag::value)
                    .findFirst()
                    .orElse("");

            List<String> sgIds = inst.securityGroups().stream()
                    .map(GroupIdentifier::groupId)
                    .collect(Collectors.toList());

            return LearnEc2Dto.builder()
                    .instanceId(inst.instanceId())
                    .name(name)
                    .instanceType(inst.instanceTypeAsString())
                    .state(inst.state().nameAsString())
                    .privateIp(inst.privateIpAddress() != null ? inst.privateIpAddress() : "")
                    .publicIp(inst.publicIpAddress() != null ? inst.publicIpAddress() : "")
                    .vpcId(inst.vpcId() != null ? inst.vpcId() : "")
                    .subnetId(inst.subnetId() != null ? inst.subnetId() : "")
                    .availabilityZone(inst.placement() != null ? inst.placement().availabilityZone() : "")
                    .securityGroupIds(sgIds)
                    .build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RDS 전용 DTO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 학습 화면 전용 RDS 인스턴스 DTO.
     * 기존 {@link RdsInstanceDto}에 없는 {@code vpcId}, {@code availabilityZone}, {@code multiAz}를 포함한다.
     */
    @Getter
    @Builder
    public static class LearnRdsDto {

        /** RDS DB 식별자 (예: database-1) */
        private String dbInstanceId;

        /** DB 엔진 (예: mysql, mariadb, postgres) */
        private String engine;

        /** DB 엔진 버전 (예: 8.0.33) */
        private String engineVersion;

        /** DB 인스턴스 상태 (예: available, stopped) */
        private String status;

        /** 엔드포인트 — "host:port" 형태. 생성 중일 때는 null */
        private String endpoint;

        /** DB 인스턴스 클래스 (예: db.t4g.micro) */
        private String instanceClass;

        /** 이 DB 인스턴스가 속한 VPC ID */
        private String vpcId;

        /** 가용 영역 (예: ap-northeast-2a) */
        private String availabilityZone;

        /** Multi-AZ 배포 여부 */
        private boolean multiAz;

        /** 연결된 VPC 보안 그룹 ID 목록 */
        private List<String> securityGroupIds;

        /**
         * AWS SDK {@link DBInstance}를 {@link LearnRdsDto}로 변환한다.
         *
         * @param db AWS SDK RDS DB 인스턴스 모델
         * @return 변환된 LearnRdsDto
         */
        public static LearnRdsDto from(DBInstance db) {
            String ep = db.endpoint() != null
                    ? db.endpoint().address() + ":" + db.endpoint().port()
                    : null;

            String vpcId = db.dbSubnetGroup() != null ? db.dbSubnetGroup().vpcId() : "";

            List<String> sgIds = db.vpcSecurityGroups().stream()
                    .map(software.amazon.awssdk.services.rds.model.VpcSecurityGroupMembership::vpcSecurityGroupId)
                    .collect(Collectors.toList());

            return LearnRdsDto.builder()
                    .dbInstanceId(db.dbInstanceIdentifier())
                    .engine(db.engine())
                    .engineVersion(db.engineVersion())
                    .status(db.dbInstanceStatus())
                    .endpoint(ep)
                    .instanceClass(db.dbInstanceClass())
                    .vpcId(vpcId)
                    .availabilityZone(db.availabilityZone())
                    .multiAz(Boolean.TRUE.equals(db.multiAZ()))
                    .securityGroupIds(sgIds)
                    .build();
        }
    }
}
