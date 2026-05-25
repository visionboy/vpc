package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * RDS 인스턴스 정보 응답 DTO.
 *
 * <p>VPC ID 조회 시 해당 VPC에 속한 RDS 인스턴스 목록에 사용된다.
 *
 * <p>RDS API는 vpc-id 직접 필터를 지원하지 않으므로,
 * 서비스 계층에서 dbSubnetGroup().vpcId()로 클라이언트 필터링한다.
 */
@Getter
@Builder
public class RdsInstanceDto {

    /** RDS DB 식별자 (예: database-1) */
    private String dbInstanceId;

    /** DB 엔진 (예: mysql, mariadb, postgres) */
    private String engine;

    /** DB 엔진 버전 (예: 8.0.33) */
    private String engineVersion;

    /** DB 인스턴스 상태 (예: available, stopped, creating) */
    private String status;

    /** 엔드포인트 — "host:port" 형태로 조합 (예: database-1.xxxxx.ap-northeast-2.rds.amazonaws.com:3306) */
    private String endpoint;

    /** DB 인스턴스 클래스 (예: db.t4g.micro, db.r6g.large) */
    private String instanceClass;

    /** 이 DB 인스턴스에 연결된 VPC 보안 그룹 ID 목록 — [상세] 버튼 클릭 시 조회에 사용 */
    private List<String> securityGroupIds;
}
