package com.company.vpc.service.aws;

import com.company.vpc.config.AwsClientFactory;
import com.company.vpc.dto.response.*;
import com.company.vpc.exception.BusinessException;
import com.company.vpc.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AWS 리소스 조회 전용 서비스 — VPC 내 EC2, RDS, 보안 그룹 상세 조회.
 *
 * <p>Peering 생성 화면에서 사용자가 VPC ID를 입력하면 해당 VPC의 리소스를 조회해
 * 현황을 확인할 수 있도록 한다.
 *
 * <p>요청자·수락자 계정을 동적으로 전환하는 방식:
 * <ul>
 *   <li>account 파라미터 "accepter" → accepter 계정 클라이언트 사용</li>
 *   <li>그 외(기본값 "requester") → requester 계정 클라이언트 사용</li>
 * </ul>
 *
 * <p>account 파라미터로 임의 계정을 지정할 수 있어 application.yml에 계정만 추가하면
 * 코드 변경 없이 새 계정 리소스를 조회할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AwsResourceQueryService {

    /** account 이름으로 EC2·RDS 클라이언트를 동적으로 조회하는 팩토리 */
    private final AwsClientFactory clientFactory;

    // ─────────────────────────────────────────────────────────────────────────
    // VPC 정보 조회
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VPC ID로 AWS Name 태그 값 조회.
     *
     * <p>DescribeVpcs 응답의 태그 목록에서 "Name" 키를 찾아 반환한다.
     * 태그가 없으면 빈 문자열을 반환하며 예외를 던지지 않는다.
     *
     * @param vpcId   조회할 VPC ID (예: vpc-0abc123)
     * @param account "requester" 또는 application.yml accounts 맵의 키
     * @return VPC Name 태그 값, 없으면 빈 문자열
     */
    public String getVpcName(String vpcId, String account) {
        try {
            DescribeVpcsResponse res = resolveEc2(account).describeVpcs(
                    DescribeVpcsRequest.builder().vpcIds(vpcId).build());
            return res.vpcs().stream()
                    .findFirst()
                    .map(vpc -> tagValue(vpc.tags(), "Name"))
                    .orElse("");
        } catch (Exception e) {
            log.warn("VPC Name 태그 조회 실패: vpcId={}, {}", vpcId, e.getMessage());
            return "";
        }
    }

    /**
     * VPC ID로 기본 CIDR 블록 조회.
     *
     * <p>AWS DescribeVpcs API를 호출해 첫 번째 IPv4 CIDR을 반환한다.
     * Jumphost 이전 폼에서 VPC ID 입력 후 CIDR 자동 완성에 사용된다.
     *
     * @param vpcId   조회할 VPC ID (예: vpc-0abc123)
     * @param account "requester" 또는 application.yml accounts 맵의 키
     * @return VPC의 기본 IPv4 CIDR (예: 10.3.0.0/16)
     * @throws BusinessException VPC 미존재 또는 AWS API 오류 시
     */
    public String getVpcCidr(String vpcId, String account) {
        try {
            DescribeVpcsResponse res = resolveEc2(account).describeVpcs(
                    DescribeVpcsRequest.builder().vpcIds(vpcId).build());
            return res.vpcs().stream()
                    .findFirst()
                    .map(Vpc::cidrBlock)
                    .orElseThrow(() -> new BusinessException(ErrorCode.AWS_API_ERROR,
                            "VPC를 찾을 수 없습니다: " + vpcId));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("VPC CIDR 조회 실패: vpcId={}, {}", vpcId, e.getMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR,
                    "VPC 조회 실패: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 라우팅 테이블 조회
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VPC ID 기준 라우팅 테이블 목록 조회.
     * Main 라우팅 테이블이 목록 맨 앞에 오도록 정렬한다.
     *
     * @param vpcId   조회할 VPC ID (예: vpc-0abc123)
     * @param account "accepter" 또는 "requester" (기본값)
     * @return 라우팅 테이블 목록 (Main 우선 정렬)
     * @throws BusinessException AWS API 오류 시
     */
    public List<RouteTableDto> getRouteTablesByVpcId(String vpcId, String account) {
        Ec2Client client = resolveEc2(account);
        try {
            DescribeRouteTablesResponse res = client.describeRouteTables(
                    DescribeRouteTablesRequest.builder()
                            .filters(Filter.builder().name("vpc-id").values(vpcId).build())
                            .build());

            return res.routeTables().stream()
                    .map(this::toRouteTableDto)
                    .sorted((a, b) -> Boolean.compare(b.isMain(), a.isMain()))  // Main 라우팅 테이블 우선 정렬
                    .collect(Collectors.toList());

        } catch (Ec2Exception e) {
            log.error("라우팅 테이블 조회 실패: vpcId={}, {}", vpcId, e.getMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR,
                    "라우팅 테이블 조회 실패: " + e.awsErrorDetails().errorMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VPC 리소스 조회 (EC2 + RDS)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VPC ID 기준 EC2 + RDS 인스턴스 목록 조회.
     *
     * <p>EC2와 RDS는 독립적으로 조회한다.
     * RDS 조회 오류 시 rdsError 필드에 오류 메시지를 담고 EC2 결과는 정상 반환한다.
     * 이는 RDS를 사용하지 않는 계정이거나 IAM 권한이 없는 경우를 gracefully 처리하기 위함이다.
     *
     * @param vpcId   조회할 VPC ID
     * @param account "accepter" 또는 "requester"
     */
    public VpcResourceResponse getVpcResources(String vpcId, String account) {
        List<Ec2InstanceDto> ec2List = queryEc2Instances(resolveEc2(account), vpcId);

        List<RdsInstanceDto> rdsList = Collections.emptyList();
        String rdsError = null;
        try {
            rdsList = queryRdsInstances(resolveRds(account), vpcId);
        } catch (Exception e) {
            // RDS 오류(IAM 권한 없음 등)는 EC2 결과에 영향을 주지 않도록 별도 처리
            log.error("RDS 조회 오류: vpcId={}, account={}", vpcId, account, e);
            rdsError = e.getMessage();
        }

        return VpcResourceResponse.builder()
                .vpcId(vpcId)
                .ec2Instances(ec2List)
                .rdsInstances(rdsList)
                .rdsError(rdsError)
                .build();
    }

    /**
     * EC2 인스턴스 목록 조회 (vpc-id 필터 + 실행 중 상태 필터).
     * terminated 상태는 제외한다.
     */
    private List<Ec2InstanceDto> queryEc2Instances(Ec2Client client, String vpcId) {
        try {
            DescribeInstancesResponse res = client.describeInstances(
                    DescribeInstancesRequest.builder()
                            .filters(
                                    Filter.builder().name("vpc-id").values(vpcId).build(),
                                    // terminated 상태 제외 — 삭제된 인스턴스 노이즈 방지
                                    Filter.builder().name("instance-state-name")
                                            .values("running", "stopped", "pending", "stopping").build()
                            ).build());

            return res.reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .map(this::toEc2Dto)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("EC2 인스턴스 조회 실패: vpcId={}, {}", vpcId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * RDS 인스턴스 목록 조회 — marker 기반 전체 페이지 순회 후 VPC ID로 클라이언트 필터링.
     *
     * <p>AWS RDS DescribeDBInstances API는 vpc-id 직접 필터를 지원하지 않으므로
     * 전체 인스턴스를 페이지별로 조회한 뒤 {@code dbSubnetGroup().vpcId()}로 필터링한다.
     *
     * <p>기본 maxRecords(100)만 조회하면 인스턴스가 많을 경우 누락될 수 있으므로
     * marker가 없어질 때까지 반복 조회한다.
     *
     * @throws Exception AWS API 오류 시 (IAM 권한 없음 포함) — 호출자가 처리
     */
    private List<RdsInstanceDto> queryRdsInstances(RdsClient client, String vpcId) {
        // RDS API는 vpc-id 직접 필터 미지원 → marker 기반 전체 페이지 조회 후 클라이언트 필터링
        List<DBInstance> all = new ArrayList<>();
        String marker = null;
        do {
            DescribeDbInstancesRequest.Builder req =
                    DescribeDbInstancesRequest.builder().maxRecords(100);
            if (marker != null) req.marker(marker);

            DescribeDbInstancesResponse res = client.describeDBInstances(req.build());
            all.addAll(res.dbInstances());
            marker = res.marker();  // null 또는 빈 문자열이면 마지막 페이지
        } while (marker != null && !marker.isEmpty());

        log.debug("RDS 전체 인스턴스 수: {}, vpcId={} 필터링 중", all.size(), vpcId);

        List<RdsInstanceDto> result = all.stream()
                .filter(db -> db.dbSubnetGroup() != null
                        && vpcId.equals(db.dbSubnetGroup().vpcId()))
                .map(this::toRdsDto)
                .collect(Collectors.toList());

        log.debug("RDS 필터링 결과: {}개 (vpcId={})", result.size(), vpcId);
        return result;
    }

    /**
     * 라우팅 테이블 ID 기준 상세 조회 — 경로(Route) 항목 목록 포함.
     *
     * <p>변경 내역 비교 화면에서 이후(TO-BE) 상태를 스냅샷과 동일한 구조로 반환하기 위해 사용한다.
     *
     * @param routeTableId 조회할 라우팅 테이블 ID (예: rtb-0abc123)
     * @param account      "accepter" 또는 "requester"
     * @return 경로 목록 포함 라우팅 테이블 상세
     * @throws BusinessException 라우팅 테이블이 존재하지 않거나 AWS API 오류 시
     */
    public RouteTableDetailDto getRouteTableDetail(String routeTableId, String account) {
        Ec2Client client = resolveEc2(account);
        try {
            DescribeRouteTablesResponse res = client.describeRouteTables(
                    DescribeRouteTablesRequest.builder()
                            .routeTableIds(routeTableId)
                            .build());

            if (res.routeTables().isEmpty()) {
                throw new BusinessException(ErrorCode.AWS_API_ERROR,
                        "라우팅 테이블을 찾을 수 없습니다: " + routeTableId);
            }

            RouteTable rt = res.routeTables().get(0);
            List<RouteTableDetailDto.RouteEntry> routes = rt.routes().stream()
                    .map(r -> RouteTableDetailDto.RouteEntry.builder()
                            .destinationCidrBlock(r.destinationCidrBlock())
                            .vpcPeeringConnectionId(r.vpcPeeringConnectionId())
                            .gatewayId(r.gatewayId())
                            .state(r.stateAsString())
                            .build())
                    .collect(Collectors.toList());

            return RouteTableDetailDto.builder()
                    .routeTableId(rt.routeTableId())
                    .vpcId(rt.vpcId())
                    .routes(routes)
                    .build();

        } catch (BusinessException e) {
            throw e;
        } catch (Ec2Exception e) {
            log.error("라우팅 테이블 상세 조회 실패: rtbId={}, {}", routeTableId, e.getMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR,
                    "라우팅 테이블 상세 조회 실패: " + e.awsErrorDetails().errorMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 보안 그룹 상세 조회
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VPC ID 기준 EC2 인스턴스의 모든 보안 그룹 상세 조회 (인바운드·아웃바운드 규칙 포함).
     *
     * <p>변경 내역 비교 팝업에서 {@code SECURITY_GROUP_POST} 스냅샷이 없을 때 실시간 폴백으로 사용된다.
     *
     * @param vpcId   조회할 VPC ID
     * @param account "accepter" 또는 "requester"
     * @return VPC 내 EC2 인스턴스에 연결된 모든 보안 그룹 상세 목록
     */
    public List<SecurityGroupDetailDto> getVpcSecurityGroups(String vpcId, String account) {
        VpcResourceResponse resources = getVpcResources(vpcId, account);

        Set<String> sgIds = new LinkedHashSet<>();
        resources.getEc2Instances().forEach(ec2 -> sgIds.addAll(ec2.getSecurityGroupIds()));
        resources.getRdsInstances().forEach(rds -> sgIds.addAll(rds.getSecurityGroupIds()));

        if (sgIds.isEmpty()) {
            return Collections.emptyList();
        }
        return getSecurityGroupDetails(new java.util.ArrayList<>(sgIds), account);
    }

    /**
     * 보안 그룹 ID 목록으로 상세 조회 (인바운드·아웃바운드 규칙 포함).
     *
     * @param sgIds   조회할 보안 그룹 ID 목록 (예: ["sg-abc", "sg-def"])
     * @param account "accepter" 또는 "requester"
     * @throws BusinessException AWS API 오류 시
     */
    public List<SecurityGroupDetailDto> getSecurityGroupDetails(List<String> sgIds, String account) {
        if (sgIds == null || sgIds.isEmpty()) return Collections.emptyList();

        try {
            DescribeSecurityGroupsResponse res = resolveEc2(account).describeSecurityGroups(
                    DescribeSecurityGroupsRequest.builder().groupIds(sgIds).build());

            return res.securityGroups().stream()
                    .map(this::toSgDetailDto)
                    .collect(Collectors.toList());

        } catch (Ec2Exception e) {
            log.error("보안 그룹 조회 실패: sgIds={}, {}", sgIds, e.getMessage());
            throw new BusinessException(ErrorCode.AWS_API_ERROR,
                    "보안 그룹 조회 실패: " + e.awsErrorDetails().errorMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 변환 메서드 (AWS SDK 모델 → DTO)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AWS RouteTable → {@link RouteTableDto} 변환.
     * Main 여부는 association 중 main=true인 항목 존재로 판단한다.
     */
    private RouteTableDto toRouteTableDto(RouteTable rt) {
        String name = tagValue(rt.tags(), "Name");
        boolean isMain = rt.associations().stream()
                .anyMatch(a -> Boolean.TRUE.equals(a.main()));
        List<String> subnetIds = rt.associations().stream()
                .filter(a -> StringUtils.hasText(a.subnetId()))
                .map(RouteTableAssociation::subnetId)
                .collect(Collectors.toList());

        return RouteTableDto.builder()
                .routeTableId(rt.routeTableId())
                .vpcId(rt.vpcId())
                .main(isMain)
                .name(name)
                .subnetIds(subnetIds)
                .build();
    }

    /**
     * AWS Instance → {@link Ec2InstanceDto} 변환.
     * 인스턴스에 연결된 모든 보안 그룹 ID를 수집한다.
     */
    private Ec2InstanceDto toEc2Dto(Instance inst) {
        List<String> sgIds = inst.securityGroups().stream()
                .map(GroupIdentifier::groupId)
                .collect(Collectors.toList());

        return Ec2InstanceDto.builder()
                .instanceId(inst.instanceId())
                .name(tagValue(inst.tags(), "Name"))
                .instanceType(inst.instanceTypeAsString())
                .state(inst.state().nameAsString())
                .privateIp(inst.privateIpAddress())
                .securityGroupIds(sgIds)
                .build();
    }

    /**
     * AWS DBInstance → {@link RdsInstanceDto} 변환.
     * endpoint는 "host:port" 형태로 조합한다. endpoint가 null이면(생성 중 등) null을 그대로 반환.
     */
    private RdsInstanceDto toRdsDto(DBInstance db) {
        List<String> sgIds = db.vpcSecurityGroups().stream()
                .map(software.amazon.awssdk.services.rds.model.VpcSecurityGroupMembership::vpcSecurityGroupId)
                .collect(Collectors.toList());

        String endpoint = db.endpoint() != null
                ? db.endpoint().address() + ":" + db.endpoint().port()
                : null;

        return RdsInstanceDto.builder()
                .dbInstanceId(db.dbInstanceIdentifier())
                .engine(db.engine())
                .engineVersion(db.engineVersion())
                .status(db.dbInstanceStatus())
                .endpoint(endpoint)
                .instanceClass(db.dbInstanceClass())
                .securityGroupIds(sgIds)
                .build();
    }

    /**
     * AWS SecurityGroup → {@link SecurityGroupDetailDto} 변환.
     * 인바운드·아웃바운드 규칙 모두 {@link #toSgRules}로 변환한다.
     */
    private SecurityGroupDetailDto toSgDetailDto(SecurityGroup sg) {
        return SecurityGroupDetailDto.builder()
                .groupId(sg.groupId())
                .groupName(sg.groupName())
                .description(sg.description())
                .inboundRules(toSgRules(sg.ipPermissions()))
                .outboundRules(toSgRules(sg.ipPermissionsEgress()))
                .build();
    }

    /**
     * AWS IpPermission 목록을 {@link SecurityGroupDetailDto.SgRule} 목록으로 변환.
     *
     * <p>source 유형이 3가지이므로 각각 처리한다:
     * <ul>
     *   <li>IpRange — IPv4 CIDR (예: 0.0.0.0/0)</li>
     *   <li>Ipv6Range — IPv6 CIDR (예: ::/0)</li>
     *   <li>UserIdGroupPair — 보안 그룹 참조 (예: sg-0abc123)</li>
     * </ul>
     * 하나의 IpPermission이 여러 source를 가질 수 있으므로 각각 개별 SgRule로 분리한다.
     */
    private List<SecurityGroupDetailDto.SgRule> toSgRules(List<IpPermission> permissions) {
        List<SecurityGroupDetailDto.SgRule> rules = new ArrayList<>();
        for (IpPermission p : permissions) {
            // -1 프로토콜은 AWS에서 "All traffic"을 의미
            String proto = "-1".equals(p.ipProtocol()) ? "ALL" : p.ipProtocol().toUpperCase();

            // IPv4 CIDR 기반 규칙
            for (IpRange r : p.ipRanges()) {
                rules.add(SecurityGroupDetailDto.SgRule.builder()
                        .protocol(proto)
                        .fromPort(p.fromPort())
                        .toPort(p.toPort())
                        .source(r.cidrIp())
                        .description(r.description())
                        .build());
            }
            // IPv6 CIDR 기반 규칙
            for (Ipv6Range r : p.ipv6Ranges()) {
                rules.add(SecurityGroupDetailDto.SgRule.builder()
                        .protocol(proto)
                        .fromPort(p.fromPort())
                        .toPort(p.toPort())
                        .source(r.cidrIpv6())
                        .description(r.description())
                        .build());
            }
            // 보안 그룹 참조 규칙 — source에 보안 그룹 ID를 저장
            for (UserIdGroupPair g : p.userIdGroupPairs()) {
                rules.add(SecurityGroupDetailDto.SgRule.builder()
                        .protocol(proto)
                        .fromPort(p.fromPort())
                        .toPort(p.toPort())
                        .source(g.groupId())
                        .description(g.description())
                        .build());
            }
        }
        return rules;
    }

    /**
     * AWS Tag 목록에서 특정 key의 value를 찾아 반환한다.
     * 해당 key가 없으면 빈 문자열을 반환한다.
     */
    private String tagValue(List<Tag> tags, String key) {
        return tags.stream()
                .filter(t -> key.equals(t.key()))
                .map(Tag::value)
                .findFirst()
                .orElse("");
    }

    /**
     * account 이름으로 EC2 클라이언트를 반환한다.
     *
     * @param account "requester", "accepter", 또는 application.yml accounts 맵의 임의 키
     * @throws com.company.vpc.exception.BusinessException 등록되지 않은 account 이름일 때
     */
    private Ec2Client resolveEc2(String account) {
        return clientFactory.getEc2Client(account);
    }

    /**
     * account 이름으로 RDS 클라이언트를 반환한다.
     *
     * @param account "requester", "accepter", 또는 application.yml accounts 맵의 임의 키
     * @throws com.company.vpc.exception.BusinessException 등록되지 않은 account 이름일 때
     */
    private RdsClient resolveRds(String account) {
        return clientFactory.getRdsClient(account);
    }
}
