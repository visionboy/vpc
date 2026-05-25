package com.company.vpc.service.aws;

import com.company.vpc.domain.PeeringHistory;
import com.company.vpc.domain.enums.CspType;
import com.company.vpc.domain.enums.SnapshotDataType;
import com.company.vpc.dto.request.PeeringCreateRequest;
import com.company.vpc.dto.response.PeeringResultDto;
import com.company.vpc.exception.BusinessException;
import com.company.vpc.exception.ErrorCode;
import com.company.vpc.config.AwsClientFactory;
import com.company.vpc.service.CspPeeringService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.VpcSecurityGroupMembership;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AWS 기반 VPC Peering 연산 구현체 — {@link CspPeeringService} 전략 구현.
 *
 * <p>요청자(Requester)·수락자(Accepter) 계정 각각의 EC2 클라이언트를 사용하며,
 * 두 클라이언트는 {@link com.company.vpc.config.AwsClientFactory}에서 동적 생성·캐시된다.
 *
 * <p>생성 5단계:
 * <ol>
 *   <li>Peering 연결 요청 (요청자 → 수락자)</li>
 *   <li>Peering 수락 (수락자 계정)</li>
 *   <li>요청자 라우팅 테이블에 수락자 CIDR 경로 추가</li>
 *   <li>수락자 라우팅 테이블에 요청자 CIDR 경로 추가</li>
 *   <li>수락자 보안 그룹에 요청자 CIDR 출처 ICMP 인바운드 허용 (선택)</li>
 * </ol>
 *
 * <p>중간 단계 실패 시 {@link CompensationContext}로 완료된 단계를 역순 롤백한다.
 *
 * <p>삭제 역순: SG 규칙 → 수락자 라우팅 → 요청자 라우팅 → Peering 연결 삭제.
 *
 * <p>계정 이름 "requester"/"accepter"는 application.yml의 aws.accounts 키와 일치해야 한다.
 * 새 accepter 계정 추가 시 yml에 항목만 추가하면 코드 변경 없이 동작한다.
 */
@Service
@Slf4j
public class AwsPeeringServiceImpl implements CspPeeringService {

    /** VPC Peering 생성 요청, 라우팅 추가, Peering 삭제에 사용하는 요청자 계정 EC2 클라이언트 */
    private final Ec2Client ec2Requester;

    /** Peering 수락, 수락자 라우팅·SG 설정에 사용하는 수락자 계정 EC2 클라이언트 */
    private final Ec2Client ec2Accepter;

    /** 수락자 VPC의 RDS 인스턴스 SG 규칙 추가·제거에 사용하는 수락자 계정 RDS 클라이언트 */
    private final RdsClient rdsAccepter;

    /** 스냅샷 데이터를 JSON으로 직렬화하는 데 사용 */
    private final ObjectMapper objectMapper;

    public AwsPeeringServiceImpl(AwsClientFactory clientFactory, ObjectMapper objectMapper) {
        this.ec2Requester = clientFactory.getEc2Client("requester");
        this.ec2Accepter  = clientFactory.getEc2Client("accepter");
        this.rdsAccepter  = clientFactory.getRdsClient("accepter");
        this.objectMapper = objectMapper;
    }

    @Override
    public CspType getCspType() {
        return CspType.AWS;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE: 5단계 순차 실행 + 보상 트랜잭션
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AWS VPC Peering 생성 5단계 실행.
     *
     * <p>각 단계 완료 시 {@link CompensationContext}에 기록해두고,
     * 이후 단계에서 예외 발생 시 기록된 단계만 역순으로 롤백한다.
     */
    @Override
    public PeeringResultDto createPeering(PeeringCreateRequest req) {
        CompensationContext ctx = new CompensationContext(req);

        try {
            // 1단계: 요청자 계정에서 수락자 VPC로 Peering 연결 요청
            String peeringId = requestPeeringConnection(req);
            ctx.setPeeringConnectionId(peeringId);
            log.info("VPC Peering 요청 완료: peeringId={}", peeringId);

            // 2단계: 수락자 계정에서 Peering 수락
            acceptPeeringConnection(peeringId);
            log.info("VPC Peering 수락 완료: peeringId={}", peeringId);

            // 3단계: 요청자 라우팅 테이블 → 수락자 CIDR 경로 추가
            addRoute(ec2Requester, req.getRequesterRouteTableId(), req.getAccepterCidr(), peeringId);
            ctx.setRequesterRouteAdded(true);
            log.info("요청자 라우팅 추가 완료: rtb={}, cidr={}", req.getRequesterRouteTableId(), req.getAccepterCidr());

            // 4단계: 수락자 라우팅 테이블 → 요청자 CIDR 경로 추가
            addRoute(ec2Accepter, req.getAccepterRouteTableId(), req.getRequesterCidr(), peeringId);
            ctx.setAccepterRouteAdded(true);
            log.info("수락자 라우팅 추가 완료: rtb={}, cidr={}", req.getAccepterRouteTableId(), req.getRequesterCidr());

            // 5단계: 수락자 보안 그룹에 ICMP 인바운드 규칙 추가 (SG 지정 시에만)
            if (StringUtils.hasText(req.getAccepterSecurityGroupId())) {
                addIcmpIngressRule(req.getAccepterSecurityGroupId(), req.getRequesterCidr());
                ctx.setSgRuleAdded(true);
                log.info("보안 그룹 ICMP 규칙 추가 완료: sg={}, cidr={}", req.getAccepterSecurityGroupId(), req.getRequesterCidr());
            }

            // 6단계: 수락자 VPC의 EC2 인스턴스 보안 그룹에 SSH(TCP 22) 인바운드 규칙 추가
            // 5단계의 단일 SG ICMP 추가와 달리, VPC 내 모든 EC2 인스턴스 SG를 갱신한다.
            // Jumphost 접근은 SSH이므로 TCP 22만 허용한다.
            if (StringUtils.hasText(req.getAccepterVpcId())) {
                Set<String> ec2SgUpdated = addEc2SgRules(req.getAccepterVpcId(), req.getRequesterCidr());
                ctx.setEc2SgRulesAdded(ec2SgUpdated);
                if (!ec2SgUpdated.isEmpty()) {
                    log.info("EC2 SG 규칙 추가 완료: VPC={}, 대상 SG 수={}", req.getAccepterVpcId(), ec2SgUpdated.size());
                }
            }

            // 7단계: 수락자 VPC의 RDS 인스턴스 보안 그룹에 TCP 인바운드 규칙 추가
            // RDS는 자체 SG를 가지며 엔드포인트 포트(MySQL=3306 등)로 특정 포트만 허용한다.
            if (StringUtils.hasText(req.getAccepterVpcId())) {
                Map<String, Integer> rdsSgUpdated = addRdsSgRules(req.getAccepterVpcId(), req.getRequesterCidr());
                ctx.setRdsSgRulesAdded(rdsSgUpdated);
                if (!rdsSgUpdated.isEmpty()) {
                    log.info("RDS SG TCP 규칙 추가 완료: VPC={}, 대상 SG 수={}", req.getAccepterVpcId(), rdsSgUpdated.size());
                }
            }

            return PeeringResultDto.builder()
                    .peeringConnectionId(peeringId)
                    .status("ACTIVE")
                    .message("VPC Peering 연결이 성공적으로 완료되었습니다.")
                    .build();

        } catch (Exception e) {
            log.error("VPC Peering 생성 실패. 보상 트랜잭션 실행: {}", e.getMessage(), e);
            rollback(ctx);
            throw new BusinessException(ErrorCode.PEERING_CREATION_FAILED, e.getMessage());
        }
    }

    /**
     * 1단계: VPC Peering 연결 요청.
     * 수락자 계정 ID가 있으면 크로스 계정 Peering으로 요청한다.
     */
    private String requestPeeringConnection(PeeringCreateRequest req) {
        CreateVpcPeeringConnectionRequest.Builder builder = CreateVpcPeeringConnectionRequest.builder()
                .vpcId(req.getRequesterVpcId())
                .peerVpcId(req.getAccepterVpcId());

        if (StringUtils.hasText(req.getAccepterAccountId())) {
            builder.peerOwnerId(req.getAccepterAccountId());
        }

        CreateVpcPeeringConnectionResponse response = ec2Requester.createVpcPeeringConnection(builder.build());
        return response.vpcPeeringConnection().vpcPeeringConnectionId();
    }

    /**
     * 2단계: 수락자 계정에서 Peering 요청 수락.
     *
     * @param peeringId AWS Peering Connection ID (pcx-xxxxx)
     */
    private void acceptPeeringConnection(String peeringId) {
        ec2Accepter.acceptVpcPeeringConnection(
                AcceptVpcPeeringConnectionRequest.builder()
                        .vpcPeeringConnectionId(peeringId)
                        .build()
        );
    }

    /**
     * 라우팅 테이블에 Peering 경로 추가.
     *
     * @param client           EC2 클라이언트 (요청자 or 수락자)
     * @param routeTableId     대상 라우팅 테이블 ID
     * @param destinationCidr  목적지 CIDR (상대방 VPC CIDR)
     * @param peeringId        Peering Connection ID (nexthop)
     */
    private void addRoute(Ec2Client client, String routeTableId, String destinationCidr, String peeringId) {
        client.createRoute(
                CreateRouteRequest.builder()
                        .routeTableId(routeTableId)
                        .destinationCidrBlock(destinationCidr)
                        .vpcPeeringConnectionId(peeringId)
                        .build()
        );
    }

    /**
     * 5단계: 수락자 보안 그룹에 요청자 CIDR 출처 ICMP 전체 허용 규칙 추가.
     * fromPort/toPort -1은 AWS에서 "모든 ICMP 유형/코드"를 의미한다.
     */
    private void addIcmpIngressRule(String sgId, String sourceCidr) {
        ec2Accepter.authorizeSecurityGroupIngress(
                AuthorizeSecurityGroupIngressRequest.builder()
                        .groupId(sgId)
                        .ipPermissions(
                                IpPermission.builder()
                                        .ipProtocol("icmp")
                                        .fromPort(-1)   // 모든 ICMP 유형
                                        .toPort(-1)     // 모든 ICMP 코드
                                        .ipRanges(IpRange.builder()
                                                .cidrIp(sourceCidr)
                                                .description("VPC Peering ICMP from " + sourceCidr)
                                                .build())
                                        .build()
                        )
                        .build()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SNAPSHOT: 삭제 전 라우팅 테이블·보안 그룹 상태 수집
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 삭제 전 네트워크 구성 스냅샷 수집.
     *
     * <p>스냅샷 수집 실패는 경고 로그만 남기고 무시한다.
     * 스냅샷 오류가 삭제 프로세스를 막으면 안 되기 때문이다.
     *
     * <p>보안 그룹: {@code accepterVpcId}가 있으면 VPC 내 전체 EC2 SG를 배열로 수집한다.
     * VPC ID가 없을 때만 {@code accepterSecurityGroupId} 단일 SG로 폴백한다.
     *
     * @param history 삭제 대상 Peering 이력
     * @return key: "ROUTE_TABLE_REQUESTER" 등 식별자, value: JSON 문자열
     */
    @Override
    public Map<String, String> captureSnapshots(PeeringHistory history) {
        Map<String, String> snapshots = new LinkedHashMap<>();

        try {
            // 요청자 라우팅 테이블 스냅샷
            if (StringUtils.hasText(history.getRequesterRouteTableId())) {
                String rtbJson = describeRouteTable(ec2Requester, history.getRequesterRouteTableId());
                snapshots.put(SnapshotDataType.ROUTE_TABLE.name() + "_REQUESTER", rtbJson);
            }

            // 수락자 라우팅 테이블 스냅샷
            if (StringUtils.hasText(history.getAccepterRouteTableId())) {
                String rtbJson = describeRouteTable(ec2Accepter, history.getAccepterRouteTableId());
                snapshots.put(SnapshotDataType.ROUTE_TABLE.name() + "_ACCEPTER", rtbJson);
            }

            // 수락자 보안 그룹 스냅샷 — VPC 전체 EC2 SG 우선, 없으면 단일 SG 폴백
            if (StringUtils.hasText(history.getAccepterVpcId())) {
                String sgArrayJson = captureVpcSecurityGroups(history.getAccepterVpcId());
                snapshots.put(SnapshotDataType.SECURITY_GROUP.name(), sgArrayJson);
            } else if (StringUtils.hasText(history.getAccepterSecurityGroupId())) {
                String sgJson = describeSecurityGroup(ec2Accepter, history.getAccepterSecurityGroupId());
                snapshots.put(SnapshotDataType.SECURITY_GROUP.name(), sgJson);
            }
        } catch (Exception e) {
            log.warn("스냅샷 수집 중 일부 오류 발생 (삭제는 계속 진행): {}", e.getMessage());
        }

        return snapshots;
    }

    /**
     * 수락자 VPC 내 모든 EC2 인스턴스의 보안 그룹 상태를 JSON 배열로 수집.
     *
     * <p>반환값은 {@code SecurityGroupDetailDto} 호환 JSON 배열({@code inboundRules}·{@code outboundRules} 포함)로,
     * 이전 전(SECURITY_GROUP)·이전 후(SECURITY_GROUP_POST) 스냅샷으로 동일하게 저장된다.
     *
     * @param accepterVpcId 수락자 VPC ID
     * @return 보안 그룹 목록 JSON 배열 (수집 실패 시 "[]")
     */
    @Override
    public String captureVpcSecurityGroups(String accepterVpcId) {
        try {
            // EC2 인스턴스의 보안 그룹 ID 수집 (non-terminated 상태만)
            Set<String> sgIds = new LinkedHashSet<>();
            try {
                DescribeInstancesResponse ec2Res = ec2Accepter.describeInstances(
                        DescribeInstancesRequest.builder()
                                .filters(
                                        Filter.builder().name("vpc-id").values(accepterVpcId).build(),
                                        Filter.builder().name("instance-state-name")
                                                .values("running", "stopped", "pending", "stopping").build()
                                ).build());
                ec2Res.reservations().stream()
                        .flatMap(r -> r.instances().stream())
                        .flatMap(i -> i.securityGroups().stream())
                        .map(GroupIdentifier::groupId)
                        .forEach(sgIds::add);
            } catch (Exception e) {
                log.warn("VPC SG 캡처 — EC2 SG ID 수집 실패: vpcId={}, {}", accepterVpcId, e.getMessage());
            }

            // RDS 인스턴스의 보안 그룹 ID도 수집
            try {
                describeRdsInstancesInVpc(accepterVpcId).stream()
                        .flatMap(db -> db.vpcSecurityGroups().stream())
                        .map(VpcSecurityGroupMembership::vpcSecurityGroupId)
                        .forEach(sgIds::add);
            } catch (Exception e) {
                log.warn("VPC SG 캡처 — RDS SG ID 수집 실패: vpcId={}, {}", accepterVpcId, e.getMessage());
            }

            if (sgIds.isEmpty()) {
                log.warn("VPC SG 캡처 — 수집된 SG ID 없음: vpcId={}", accepterVpcId);
                return "[]";
            }

            // 보안 그룹 상세 조회
            DescribeSecurityGroupsResponse sgRes = ec2Accepter.describeSecurityGroups(
                    DescribeSecurityGroupsRequest.builder()
                            .groupIds(new ArrayList<>(sgIds))
                            .build());

            // SecurityGroupDetailDto 호환 JSON 배열 직렬화
            List<Map<String, Object>> sgList = sgRes.securityGroups().stream()
                    .map(sg -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("groupId", sg.groupId());
                        m.put("groupName", sg.groupName());
                        m.put("description", sg.description());
                        m.put("inboundRules",  toSgRuleMapList(sg.ipPermissions()));
                        m.put("outboundRules", toSgRuleMapList(sg.ipPermissionsEgress()));
                        return m;
                    }).collect(Collectors.toList());

            return toJson(sgList);
        } catch (Exception e) {
            log.warn("VPC 보안 그룹 스냅샷 수집 실패: vpcId={}, msg={}", accepterVpcId, e.getMessage());
            return "[]";
        }
    }

    /**
     * AWS IpPermission 목록을 {@code SecurityGroupDetailDto.SgRule} 호환 Map 목록으로 변환.
     * "-1" 프로토콜은 "ALL"로 표시하고, IPv4/IPv6 CIDR 및 보안 그룹 참조를 각각 개별 규칙으로 분리한다.
     */
    private List<Map<String, Object>> toSgRuleMapList(List<IpPermission> permissions) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (IpPermission p : permissions) {
            // -1 프로토콜은 AWS에서 "All traffic"을 의미
            String proto = "-1".equals(p.ipProtocol()) ? "ALL" : p.ipProtocol().toUpperCase();
            for (IpRange r : p.ipRanges()) {
                rules.add(buildRuleMap(proto, p.fromPort(), p.toPort(), r.cidrIp(),
                        r.description() != null ? r.description() : ""));
            }
            for (Ipv6Range r : p.ipv6Ranges()) {
                rules.add(buildRuleMap(proto, p.fromPort(), p.toPort(), r.cidrIpv6(),
                        r.description() != null ? r.description() : ""));
            }
            for (UserIdGroupPair g : p.userIdGroupPairs()) {
                rules.add(buildRuleMap(proto, p.fromPort(), p.toPort(), g.groupId(),
                        g.description() != null ? g.description() : ""));
            }
        }
        return rules;
    }

    /** 단일 SG 규칙 Map 생성 헬퍼 */
    private Map<String, Object> buildRuleMap(String protocol, Integer fromPort, Integer toPort,
                                             String source, String description) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("protocol", protocol);
        rule.put("fromPort", fromPort);
        rule.put("toPort", toPort);
        rule.put("source", source);
        rule.put("description", description);
        return rule;
    }

    /**
     * 라우팅 테이블 현재 상태를 JSON으로 직렬화.
     * 라우팅 테이블이 없으면 빈 JSON({})을 반환한다.
     */
    private String describeRouteTable(Ec2Client client, String routeTableId) {
        DescribeRouteTablesResponse response = client.describeRouteTables(
                DescribeRouteTablesRequest.builder()
                        .routeTableIds(routeTableId)
                        .build()
        );

        if (response.routeTables().isEmpty()) {
            return "{}";
        }

        RouteTable rt = response.routeTables().get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("routeTableId", rt.routeTableId());
        data.put("vpcId", rt.vpcId());
        data.put("routes", rt.routes().stream().map(r -> {
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("destinationCidrBlock", r.destinationCidrBlock());
            route.put("vpcPeeringConnectionId", r.vpcPeeringConnectionId());
            route.put("gatewayId", r.gatewayId());
            route.put("state", r.stateAsString());
            return route;
        }).collect(Collectors.toList()));

        return toJson(data);
    }

    /**
     * 보안 그룹 인바운드 규칙을 JSON으로 직렬화.
     * 보안 그룹이 없으면 빈 JSON({})을 반환한다.
     */
    private String describeSecurityGroup(Ec2Client client, String sgId) {
        DescribeSecurityGroupsResponse response = client.describeSecurityGroups(
                DescribeSecurityGroupsRequest.builder()
                        .groupIds(sgId)
                        .build()
        );

        if (response.securityGroups().isEmpty()) {
            return "{}";
        }

        SecurityGroup sg = response.securityGroups().get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", sg.groupId());
        data.put("groupName", sg.groupName());
        data.put("vpcId", sg.vpcId());
        data.put("ingressRules", sg.ipPermissions().stream().map(p -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("ipProtocol", p.ipProtocol());
            rule.put("fromPort", p.fromPort());
            rule.put("toPort", p.toPort());
            rule.put("ipRanges", p.ipRanges().stream()
                    .map(IpRange::cidrIp).collect(Collectors.toList()));
            return rule;
        }).collect(Collectors.toList()));

        return toJson(data);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE: 역순 정리 (SG 규칙 → 라우팅 → Peering 연결)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AWS Peering 관련 리소스 역순 삭제.
     *
     * <p>생성 순서의 역순으로 정리해 의존 관계 오류를 방지한다:
     * RDS SG 규칙 → EC2 SG 규칙 → 수락자 라우팅 → 요청자 라우팅 → Peering 연결
     *
     * @param history 삭제할 Peering 이력 (peeringConnectionId, 라우팅·SG ID 포함)
     */
    @Override
    public void deletePeering(PeeringHistory history) {
        String peeringId = history.getPeeringConnectionId();

        // 0단계: 수락자 VPC EC2 인스턴스 보안 그룹 SSH(TCP 22) 규칙 제거
        if (StringUtils.hasText(history.getAccepterVpcId())) {
            removeEc2SgRules(history.getAccepterVpcId(), history.getRequesterCidr());
        }

        // 0-1단계: 수락자 VPC RDS 인스턴스 보안 그룹 TCP 규칙 제거
        if (StringUtils.hasText(history.getAccepterVpcId())) {
            removeRdsSgRules(history.getAccepterVpcId(), history.getRequesterCidr());
        }

        // 1단계: 수락자 보안 그룹 ICMP 인바운드 규칙 제거 — 이미 없으면 무시
        if (StringUtils.hasText(history.getAccepterSecurityGroupId())) {
            try {
                removeIcmpIngressRule(history.getAccepterSecurityGroupId(), history.getRequesterCidr());
                log.info("보안 그룹 ICMP 규칙 삭제 완료: sg={}", history.getAccepterSecurityGroupId());
            } catch (Ec2Exception e) {
                if ("InvalidPermission.NotFound".equals(e.awsErrorDetails().errorCode())) {
                    log.warn("ICMP 규칙 이미 없음 (무시): sg={}", history.getAccepterSecurityGroupId());
                } else {
                    log.error("ICMP 규칙 삭제 실패: sg={}, error={}", history.getAccepterSecurityGroupId(), e.getMessage(), e);
                    throw new BusinessException(ErrorCode.PEERING_DELETION_FAILED, e.getMessage());
                }
            }
        }

        // 2단계: 수락자 라우팅 테이블에서 요청자 CIDR 경로 제거 — 이미 없으면 무시
        if (StringUtils.hasText(history.getAccepterRouteTableId())) {
            try {
                deleteRoute(ec2Accepter, history.getAccepterRouteTableId(), history.getRequesterCidr());
                log.info("수락자 라우팅 경로 삭제 완료: rtb={}", history.getAccepterRouteTableId());
            } catch (Ec2Exception e) {
                if ("InvalidRoute.NotFound".equals(e.awsErrorDetails().errorCode())) {
                    log.warn("수락자 라우팅 경로 이미 없음 (무시): rtb={}, cidr={}",
                            history.getAccepterRouteTableId(), history.getRequesterCidr());
                } else {
                    log.error("수락자 라우팅 삭제 실패: rtb={}, error={}", history.getAccepterRouteTableId(), e.getMessage(), e);
                    throw new BusinessException(ErrorCode.PEERING_DELETION_FAILED, e.getMessage());
                }
            }
        }

        // 3단계: 요청자 라우팅 테이블에서 수락자 CIDR 경로 제거 — 이미 없으면 무시
        if (StringUtils.hasText(history.getRequesterRouteTableId())) {
            try {
                deleteRoute(ec2Requester, history.getRequesterRouteTableId(), history.getAccepterCidr());
                log.info("요청자 라우팅 경로 삭제 완료: rtb={}", history.getRequesterRouteTableId());
            } catch (Ec2Exception e) {
                if ("InvalidRoute.NotFound".equals(e.awsErrorDetails().errorCode())) {
                    log.warn("요청자 라우팅 경로 이미 없음 (무시): rtb={}, cidr={}",
                            history.getRequesterRouteTableId(), history.getAccepterCidr());
                } else {
                    log.error("요청자 라우팅 삭제 실패: rtb={}, error={}", history.getRequesterRouteTableId(), e.getMessage(), e);
                    throw new BusinessException(ErrorCode.PEERING_DELETION_FAILED, e.getMessage());
                }
            }
        }

        // 4단계: VPC Peering 연결 자체 삭제 — 이미 삭제됐거나 없는 경우 무시
        if (StringUtils.hasText(peeringId)) {
            try {
                ec2Requester.deleteVpcPeeringConnection(
                        DeleteVpcPeeringConnectionRequest.builder()
                                .vpcPeeringConnectionId(peeringId)
                                .build()
                );
                log.info("VPC Peering 연결 삭제 완료: peeringId={}", peeringId);
            } catch (Ec2Exception e) {
                String code = e.awsErrorDetails().errorCode();
                // InvalidVpcPeeringConnectionId.NotFound: 이미 삭제됨 또는 존재하지 않음
                if ("InvalidVpcPeeringConnectionId.NotFound".equals(code)
                        || "InvalidStateTransition".equals(code)) {
                    log.warn("Peering 연결 이미 없음 또는 삭제 불가 상태 (무시): peeringId={}, code={}", peeringId, code);
                } else {
                    log.error("VPC Peering 연결 삭제 실패: peeringId={}, error={}", peeringId, e.getMessage(), e);
                    throw new BusinessException(ErrorCode.PEERING_DELETION_FAILED, e.getMessage());
                }
            }
        }
    }

    /**
     * 수락자 VPC의 모든 RDS 인스턴스 보안 그룹에 요청자 CIDR TCP 인바운드 규칙 추가.
     *
     * <p>RDS 인스턴스별 엔드포인트 포트를 조회해 해당 포트로 허용 규칙을 추가한다.
     * 같은 SG가 여러 인스턴스에 공유되더라도 SG당 1회만 추가한다.
     * 이미 존재하는 규칙(InvalidPermission.Duplicate)은 경고 로그 후 무시한다.
     *
     * @param accepterVpcId 수락자 VPC ID
     * @param requesterCidr 허용할 출처 CIDR (신규 Jumphost VPC)
     * @return 업데이트된 SG ID → DB 포트 맵 (롤백 시 사용)
     */
    private Map<String, Integer> addRdsSgRules(String accepterVpcId, String requesterCidr) {
        Map<String, Integer> updated = new LinkedHashMap<>();

        for (DBInstance db : describeRdsInstancesInVpc(accepterVpcId)) {
            if (db.endpoint() == null) continue;   // 인스턴스 생성 중 — 포트 미확정
            int port = db.endpoint().port();

            for (VpcSecurityGroupMembership vsg : db.vpcSecurityGroups()) {
                String sgId = vsg.vpcSecurityGroupId();
                if (updated.containsKey(sgId)) continue;    // 동일 SG 중복 추가 방지

                try {
                    ec2Accepter.authorizeSecurityGroupIngress(
                            AuthorizeSecurityGroupIngressRequest.builder()
                                    .groupId(sgId)
                                    .ipPermissions(
                                            IpPermission.builder()
                                                    .ipProtocol("tcp")
                                                    .fromPort(port)
                                                    .toPort(port)
                                                    .ipRanges(IpRange.builder()
                                                            .cidrIp(requesterCidr)
                                                            .description("VPC Peering TCP from " + requesterCidr)
                                                            .build())
                                                    .build()
                                    )
                                    .build()
                    );
                    updated.put(sgId, port);
                    log.info("RDS SG TCP 규칙 추가: sg={}, port={}, cidr={}, db={}",
                            sgId, port, requesterCidr, db.dbInstanceIdentifier());
                } catch (Ec2Exception e) {
                    if ("InvalidPermission.Duplicate".equals(e.awsErrorDetails().errorCode())) {
                        // 이미 동일한 규칙 존재 — 롤백 추적은 유지
                        updated.put(sgId, port);
                        log.warn("RDS SG 규칙 이미 존재 (무시): sg={}, port={}", sgId, port);
                    } else {
                        log.warn("RDS SG 규칙 추가 실패 (건너뜀): sg={}, db={}, error={}",
                                sgId, db.dbInstanceIdentifier(), e.getMessage());
                    }
                }
            }
        }
        return updated;
    }

    /**
     * 수락자 VPC의 모든 RDS 인스턴스 보안 그룹에서 요청자 CIDR TCP 인바운드 규칙 제거.
     * 삭제 시점에 RDS 인스턴스를 재조회해 현재 포트 기준으로 제거한다.
     * 존재하지 않는 규칙은 경고 로그 후 무시한다.
     *
     * @param accepterVpcId 수락자 VPC ID
     * @param requesterCidr 제거할 출처 CIDR
     */
    private void removeRdsSgRules(String accepterVpcId, String requesterCidr) {
        Set<String> processed = new HashSet<>();

        for (DBInstance db : describeRdsInstancesInVpc(accepterVpcId)) {
            if (db.endpoint() == null) continue;
            int port = db.endpoint().port();

            for (VpcSecurityGroupMembership vsg : db.vpcSecurityGroups()) {
                String sgId = vsg.vpcSecurityGroupId();
                if (!processed.add(sgId)) continue;    // Set.add()가 false면 이미 처리됨

                try {
                    ec2Accepter.revokeSecurityGroupIngress(
                            RevokeSecurityGroupIngressRequest.builder()
                                    .groupId(sgId)
                                    .ipPermissions(
                                            IpPermission.builder()
                                                    .ipProtocol("tcp")
                                                    .fromPort(port)
                                                    .toPort(port)
                                                    .ipRanges(IpRange.builder().cidrIp(requesterCidr).build())
                                                    .build()
                                    )
                                    .build()
                    );
                    log.info("RDS SG TCP 규칙 제거: sg={}, port={}, cidr={}", sgId, port, requesterCidr);
                } catch (Ec2Exception e) {
                    log.warn("RDS SG 규칙 제거 실패 (건너뜀): sg={}, error={}", sgId, e.getMessage());
                }
            }
        }
    }

    /**
     * 롤백용 — CompensationContext에 기록된 SG ID·포트 맵으로 정확히 제거.
     * {@link #removeRdsSgRules}와 달리 재조회 없이 생성 시 기록한 포트로 제거한다.
     *
     * @param sgPorts      SG ID → 포트 맵
     * @param requesterCidr 제거할 출처 CIDR
     */
    private void removeRdsSgRulesById(Map<String, Integer> sgPorts, String requesterCidr) {
        sgPorts.forEach((sgId, port) -> {
            try {
                ec2Accepter.revokeSecurityGroupIngress(
                        RevokeSecurityGroupIngressRequest.builder()
                                .groupId(sgId)
                                .ipPermissions(
                                        IpPermission.builder()
                                                .ipProtocol("tcp")
                                                .fromPort(port)
                                                .toPort(port)
                                                .ipRanges(IpRange.builder().cidrIp(requesterCidr).build())
                                                .build()
                                )
                                .build()
                );
                log.info("RDS SG TCP 규칙 롤백 완료: sg={}, port={}", sgId, port);
            } catch (Exception e) {
                log.warn("RDS SG TCP 규칙 롤백 실패 (건너뜀): sg={}, error={}", sgId, e.getMessage());
            }
        });
    }

    /**
     * 수락자 VPC의 모든 EC2 인스턴스 보안 그룹에 요청자 CIDR 전체 트래픽 인바운드 규칙 추가.
     *
     * <p>Jumphost 접근에 필요한 SSH(TCP 22)만 허용한다.
     * 같은 SG를 공유하는 인스턴스가 여러 개여도 SG당 1회만 추가한다.
     * 중복 규칙(InvalidPermission.Duplicate)은 경고 후 무시한다.
     *
     * @param accepterVpcId 수락자 VPC ID
     * @param requesterCidr 허용할 출처 CIDR (신규 Jumphost VPC)
     * @return 업데이트된 SG ID 집합 (롤백 시 사용)
     */
    private Set<String> addEc2SgRules(String accepterVpcId, String requesterCidr) {
        Set<String> updated = new LinkedHashSet<>();

        for (Instance inst : describeEc2InstancesInVpc(accepterVpcId)) {
            for (GroupIdentifier gi : inst.securityGroups()) {
                String sgId = gi.groupId();
                if (!updated.add(sgId)) continue;   // Set.add()가 false면 이미 처리됨

                try {
                    ec2Accepter.authorizeSecurityGroupIngress(
                            AuthorizeSecurityGroupIngressRequest.builder()
                                    .groupId(sgId)
                                    .ipPermissions(
                                            IpPermission.builder()
                                                    .ipProtocol("tcp")
                                                    .fromPort(22)
                                                    .toPort(22)
                                                    .ipRanges(IpRange.builder()
                                                            .cidrIp(requesterCidr)
                                                            .description("VPC Peering SSH from " + requesterCidr)
                                                            .build())
                                                    .build()
                                    )
                                    .build()
                    );
                    log.info("EC2 SG SSH 규칙 추가: sg={}, cidr={}, instance={}",
                            sgId, requesterCidr, inst.instanceId());
                } catch (Ec2Exception e) {
                    if ("InvalidPermission.Duplicate".equals(e.awsErrorDetails().errorCode())) {
                        log.warn("EC2 SG 규칙 이미 존재 (무시): sg={}", sgId);
                    } else {
                        // 추가 실패한 SG는 롤백 대상에서 제거
                        updated.remove(sgId);
                        log.warn("EC2 SG 규칙 추가 실패 (건너뜀): sg={}, instance={}, error={}",
                                sgId, inst.instanceId(), e.getMessage());
                    }
                }
            }
        }
        return updated;
    }

    /**
     * 수락자 VPC의 모든 EC2 인스턴스 보안 그룹에서 요청자 CIDR 전체 트래픽 규칙 제거.
     * 삭제 시점에 EC2 인스턴스를 재조회해 현재 SG 기준으로 제거한다.
     *
     * @param accepterVpcId 수락자 VPC ID
     * @param requesterCidr 제거할 출처 CIDR
     */
    private void removeEc2SgRules(String accepterVpcId, String requesterCidr) {
        Set<String> processed = new HashSet<>();

        for (Instance inst : describeEc2InstancesInVpc(accepterVpcId)) {
            for (GroupIdentifier gi : inst.securityGroups()) {
                String sgId = gi.groupId();
                if (!processed.add(sgId)) continue;

                try {
                    ec2Accepter.revokeSecurityGroupIngress(
                            RevokeSecurityGroupIngressRequest.builder()
                                    .groupId(sgId)
                                    .ipPermissions(
                                            IpPermission.builder()
                                                    .ipProtocol("tcp")
                                                    .fromPort(22)
                                                    .toPort(22)
                                                    .ipRanges(IpRange.builder().cidrIp(requesterCidr).build())
                                                    .build()
                                    )
                                    .build()
                    );
                    log.info("EC2 SG SSH 규칙 제거: sg={}, cidr={}", sgId, requesterCidr);
                } catch (Ec2Exception e) {
                    log.warn("EC2 SG SSH 규칙 제거 실패 (건너뜀): sg={}, error={}", sgId, e.getMessage());
                }
            }
        }
    }

    /**
     * 롤백용 — CompensationContext에 기록된 SG ID 집합으로 정확히 제거.
     * {@link #removeEc2SgRules}와 달리 재조회 없이 생성 시 기록한 SG만 제거한다.
     *
     * @param sgIds        생성 시 추가된 SG ID 집합
     * @param requesterCidr 제거할 출처 CIDR
     */
    private void removeEc2SgRulesById(Set<String> sgIds, String requesterCidr) {
        sgIds.forEach(sgId -> {
            try {
                ec2Accepter.revokeSecurityGroupIngress(
                        RevokeSecurityGroupIngressRequest.builder()
                                .groupId(sgId)
                                .ipPermissions(
                                        IpPermission.builder()
                                                .ipProtocol("tcp")
                                                .fromPort(22)
                                                .toPort(22)
                                                .ipRanges(IpRange.builder().cidrIp(requesterCidr).build())
                                                .build()
                                )
                                .build()
                );
                log.info("EC2 SG SSH 규칙 롤백 완료: sg={}", sgId);
            } catch (Exception e) {
                log.warn("EC2 SG 규칙 롤백 실패 (건너뜀): sg={}, error={}", sgId, e.getMessage());
            }
        });
    }

    /**
     * 수락자 VPC에 속하는 EC2 인스턴스 목록 전체 조회.
     *
     * <p>EC2 API는 vpc-id 직접 필터를 지원하므로 RDS와 달리 클라이언트 사이드 필터링이 불필요하다.
     * running/stopped 상태의 인스턴스만 조회해 terminated 인스턴스는 제외한다.
     *
     * @param vpcId 필터링할 VPC ID
     * @return 해당 VPC에 속하는 Instance 목록
     */
    private List<Instance> describeEc2InstancesInVpc(String vpcId) {
        List<Instance> result = new ArrayList<>();
        String nextToken = null;

        do {
            DescribeInstancesRequest.Builder builder = DescribeInstancesRequest.builder()
                    .filters(
                            Filter.builder().name("vpc-id").values(vpcId).build(),
                            // terminated 인스턴스 SG는 이미 해제되어 있으므로 제외
                            Filter.builder().name("instance-state-name").values("running", "stopped").build()
                    )
                    .maxResults(1000);
            if (nextToken != null) builder.nextToken(nextToken);

            DescribeInstancesResponse res = ec2Accepter.describeInstances(builder.build());
            res.reservations().forEach(r -> result.addAll(r.instances()));
            nextToken = res.nextToken();
        } while (nextToken != null && !nextToken.isEmpty());

        return result;
    }

    /**
     * 수락자 VPC에 속하는 RDS 인스턴스 목록 전체 조회.
     *
     * <p>RDS API는 vpc-id 직접 필터 미지원 → marker 기반 전체 페이지 조회 후
     * {@code dbSubnetGroup.vpcId}로 클라이언트 사이드 필터링한다.
     *
     * @param vpcId 필터링할 VPC ID
     * @return 해당 VPC에 속하는 DBInstance 목록
     */
    private List<DBInstance> describeRdsInstancesInVpc(String vpcId) {
        List<DBInstance> result = new ArrayList<>();
        String marker = null;

        do {
            DescribeDbInstancesRequest.Builder builder =
                    DescribeDbInstancesRequest.builder().maxRecords(100);
            if (marker != null) builder.marker(marker);

            DescribeDbInstancesResponse res = rdsAccepter.describeDBInstances(builder.build());
            res.dbInstances().stream()
                    .filter(db -> db.dbSubnetGroup() != null
                            && vpcId.equals(db.dbSubnetGroup().vpcId()))
                    .forEach(result::add);

            marker = res.marker();
        } while (marker != null && !marker.isEmpty());

        return result;
    }

    /**
     * 보안 그룹 ICMP 인바운드 규칙 제거 — {@link #addIcmpIngressRule}의 역연산.
     */
    private void removeIcmpIngressRule(String sgId, String sourceCidr) {
        ec2Accepter.revokeSecurityGroupIngress(
                RevokeSecurityGroupIngressRequest.builder()
                        .groupId(sgId)
                        .ipPermissions(
                                IpPermission.builder()
                                        .ipProtocol("icmp")
                                        .fromPort(-1)
                                        .toPort(-1)
                                        .ipRanges(IpRange.builder().cidrIp(sourceCidr).build())
                                        .build()
                        )
                        .build()
        );
    }

    /**
     * 라우팅 테이블에서 특정 CIDR 경로 제거 — {@link #addRoute}의 역연산.
     */
    private void deleteRoute(Ec2Client client, String routeTableId, String destinationCidr) {
        client.deleteRoute(
                DeleteRouteRequest.builder()
                        .routeTableId(routeTableId)
                        .destinationCidrBlock(destinationCidr)
                        .build()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 보상 트랜잭션 (Compensating Transaction) — 생성 실패 시 역순 롤백
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 생성 단계 중 실패 시 완료된 단계만 역순으로 롤백.
     *
     * <p>각 롤백 단계는 {@link #safeExecute}로 감싸서 롤백 오류가 원본 예외를 덮지 않도록 한다.
     */
    private void rollback(CompensationContext ctx) {
        if (!ctx.getRdsSgRulesAdded().isEmpty()) {
            safeExecute("RDS SG 규칙 롤백", () ->
                    removeRdsSgRulesById(ctx.getRdsSgRulesAdded(), ctx.getReq().getRequesterCidr()));
        }
        if (!ctx.getEc2SgRulesAdded().isEmpty()) {
            safeExecute("EC2 SG 규칙 롤백", () ->
                    removeEc2SgRulesById(ctx.getEc2SgRulesAdded(), ctx.getReq().getRequesterCidr()));
        }
        if (ctx.isSgRuleAdded()) {
            safeExecute("SG 규칙 롤백", () ->
                    removeIcmpIngressRule(ctx.getReq().getAccepterSecurityGroupId(),
                            ctx.getReq().getRequesterCidr()));
        }
        if (ctx.isAccepterRouteAdded()) {
            safeExecute("수락자 라우팅 롤백", () ->
                    deleteRoute(ec2Accepter, ctx.getReq().getAccepterRouteTableId(),
                            ctx.getReq().getRequesterCidr()));
        }
        if (ctx.isRequesterRouteAdded()) {
            safeExecute("요청자 라우팅 롤백", () ->
                    deleteRoute(ec2Requester, ctx.getReq().getRequesterRouteTableId(),
                            ctx.getReq().getAccepterCidr()));
        }
        if (ctx.getPeeringConnectionId() != null) {
            safeExecute("Peering 연결 롤백", () ->
                    ec2Requester.deleteVpcPeeringConnection(
                            DeleteVpcPeeringConnectionRequest.builder()
                                    .vpcPeeringConnectionId(ctx.getPeeringConnectionId())
                                    .build()));
        }
    }

    /**
     * 롤백 단계 실행 래퍼 — 실패해도 경고 로그만 남기고 다음 롤백 단계를 계속한다.
     * 롤백 오류가 원본 예외(실패 원인)보다 중요하지 않기 때문이다.
     */
    private void safeExecute(String stepName, Runnable action) {
        try {
            action.run();
            log.info("보상 트랜잭션 성공: {}", stepName);
        } catch (Exception e) {
            log.error("보상 트랜잭션 실패 [{}]: {}", stepName, e.getMessage());
        }
    }

    /**
     * 객체를 JSON 문자열로 변환. 직렬화 실패 시 빈 JSON({}) 반환.
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 보상 트랜잭션 상태 추적 내부 클래스
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 생성 단계별 완료 여부를 추적해 롤백 대상을 결정하는 컨텍스트 클래스.
     *
     * <p>각 단계 완료 후 해당 플래그를 true로 설정하면,
     * 이후 실패 시 {@link #rollback}이 true인 단계만 역순으로 정리한다.
     */
    private static class CompensationContext {

        /** 원본 생성 요청 — 롤백 시 라우팅·SG 식별자로 사용 */
        private final PeeringCreateRequest req;

        /** 생성된 Peering Connection ID — null이면 1단계도 완료되지 않은 것 */
        private String peeringConnectionId;

        /** 3단계(요청자 라우팅 추가) 완료 여부 */
        private boolean requesterRouteAdded;

        /** 4단계(수락자 라우팅 추가) 완료 여부 */
        private boolean accepterRouteAdded;

        /** 5단계(SG ICMP 규칙 추가) 완료 여부 */
        private boolean sgRuleAdded;

        /** 6단계(EC2 SG SSH TCP 22 규칙 추가) 완료 목록 — key: SG ID */
        private Set<String> ec2SgRulesAdded = new LinkedHashSet<>();

        /** 7단계(RDS SG TCP 규칙 추가) 완료 목록 — key: SG ID, value: DB 포트 */
        private Map<String, Integer> rdsSgRulesAdded = new LinkedHashMap<>();

        CompensationContext(PeeringCreateRequest req) {
            this.req = req;
        }

        PeeringCreateRequest getReq() { return req; }
        String getPeeringConnectionId() { return peeringConnectionId; }
        void setPeeringConnectionId(String id) { this.peeringConnectionId = id; }
        boolean isRequesterRouteAdded() { return requesterRouteAdded; }
        void setRequesterRouteAdded(boolean v) { this.requesterRouteAdded = v; }
        boolean isAccepterRouteAdded() { return accepterRouteAdded; }
        void setAccepterRouteAdded(boolean v) { this.accepterRouteAdded = v; }
        boolean isSgRuleAdded() { return sgRuleAdded; }
        void setSgRuleAdded(boolean v) { this.sgRuleAdded = v; }
        Set<String> getEc2SgRulesAdded() { return ec2SgRulesAdded; }
        void setEc2SgRulesAdded(Set<String> s) { this.ec2SgRulesAdded = s; }
        Map<String, Integer> getRdsSgRulesAdded() { return rdsSgRulesAdded; }
        void setRdsSgRulesAdded(Map<String, Integer> m) { this.rdsSgRulesAdded = m; }
    }
}
