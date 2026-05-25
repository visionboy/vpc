package com.company.vpc.controller;

import com.company.vpc.common.ApiResponse;
import com.company.vpc.dto.response.RouteTableDetailDto;
import com.company.vpc.dto.response.RouteTableDto;
import com.company.vpc.dto.response.SecurityGroupDetailDto;
import com.company.vpc.dto.response.VpcResourceResponse;
import com.company.vpc.service.aws.AwsResourceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * AWS 리소스 조회 REST 컨트롤러.
 *
 * <p>기본 URL: {@code /api/v1/aws}
 *
 * <p>Peering 생성 화면에서 VPC ID를 입력했을 때 프론트엔드가 호출하는 엔드포인트들이다.
 * 라우팅 테이블 드롭다운 자동 채우기, EC2/RDS 인스턴스 목록 표시, 보안 그룹 상세 모달에 사용된다.
 *
 * <p>account 파라미터:
 * <ul>
 *   <li>"requester" (기본값) — 요청자 계정 AWS 클라이언트 사용</li>
 *   <li>"accepter" — 수락자 계정 AWS 클라이언트 사용</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/aws")
@RequiredArgsConstructor
public class AwsQueryController {

    private final AwsResourceQueryService queryService;

    /**
     * VPC ID 기준 라우팅 테이블 목록 조회.
     * Main 라우팅 테이블이 목록 맨 앞에 정렬되어 반환된다.
     *
     * @param vpcId   조회할 VPC ID (예: vpc-0abc123)
     * @param account "requester" 또는 "accepter" (기본값: "requester")
     */
    @GetMapping("/route-tables")
    public ResponseEntity<ApiResponse<List<RouteTableDto>>> getRouteTables(
            @RequestParam String vpcId,
            @RequestParam(defaultValue = "requester") String account) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.getRouteTablesByVpcId(vpcId, account)));
    }

    /**
     * VPC ID 기준 EC2 + RDS 인스턴스 목록 조회.
     *
     * <p>RDS 조회 오류(IAM 권한 없음 등)는 응답의 {@code rdsError} 필드에 담기며
     * EC2 결과는 정상 반환된다.
     *
     * @param vpcId   조회할 VPC ID
     * @param account "requester" 또는 "accepter" (기본값: "requester")
     */
    @GetMapping("/vpc-resources")
    public ResponseEntity<ApiResponse<VpcResourceResponse>> getVpcResources(
            @RequestParam String vpcId,
            @RequestParam(defaultValue = "requester") String account) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.getVpcResources(vpcId, account)));
    }

    /**
     * VPC ID로 기본 IPv4 CIDR 조회.
     * Jumphost 이전 폼에서 VPC ID 입력 후 CIDR 자동 완성에 사용된다.
     *
     * @param vpcId   조회할 VPC ID (예: vpc-0abc123)
     * @param account "requester" 또는 "accepter" (기본값: "requester")
     * @return VPC의 기본 IPv4 CIDR 문자열 (예: "10.3.0.0/16")
     */
    @GetMapping("/vpc-cidr")
    public ResponseEntity<ApiResponse<String>> getVpcCidr(
            @RequestParam String vpcId,
            @RequestParam(defaultValue = "requester") String account) {
        return ResponseEntity.ok(ApiResponse.ok(queryService.getVpcCidr(vpcId, account)));
    }

    /**
     * VPC ID로 AWS Name 태그 값 조회.
     * 태그가 없으면 빈 문자열을 반환하며 404를 내지 않는다.
     *
     * @param vpcId   조회할 VPC ID (예: vpc-0abc123)
     * @param account "requester" 또는 "accepter" (기본값: "requester")
     * @return VPC Name 태그 값, 없으면 빈 문자열
     */
    @GetMapping("/vpc-name")
    public ResponseEntity<ApiResponse<String>> getVpcName(
            @RequestParam String vpcId,
            @RequestParam(defaultValue = "requester") String account) {
        return ResponseEntity.ok(ApiResponse.ok(queryService.getVpcName(vpcId, account)));
    }

    /**
     * VPC ID 기준 EC2 인스턴스 보안 그룹 전체 상세 조회.
     *
     * <p>변경 내역 비교 팝업에서 {@code SECURITY_GROUP_POST} 스냅샷이 없을 때 실시간 폴백으로 호출된다.
     *
     * @param vpcId   조회할 VPC ID
     * @param account "requester" 또는 "accepter" (기본값: "accepter")
     */
    @GetMapping("/vpc-security-groups")
    public ResponseEntity<ApiResponse<List<SecurityGroupDetailDto>>> getVpcSecurityGroups(
            @RequestParam String vpcId,
            @RequestParam(defaultValue = "accepter") String account) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.getVpcSecurityGroups(vpcId, account)));
    }

    /**
     * 라우팅 테이블 ID 기준 상세 조회 — 경로(Route) 항목 목록 포함.
     *
     * <p>변경 내역 비교 화면에서 이후(TO-BE) 현재 상태를 이전(AS-IS) 스냅샷과
     * 동일한 구조로 반환하기 위해 사용한다.
     *
     * @param rtbId   조회할 라우팅 테이블 ID (예: rtb-0abc123)
     * @param account "requester" 또는 "accepter" (기본값: "requester")
     */
    @GetMapping("/route-table-detail")
    public ResponseEntity<ApiResponse<RouteTableDetailDto>> getRouteTableDetail(
            @RequestParam String rtbId,
            @RequestParam(defaultValue = "requester") String account) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.getRouteTableDetail(rtbId, account)));
    }

    /**
     * 보안 그룹 상세 조회 — 인바운드·아웃바운드 규칙 포함.
     *
     * @param sgIds   쉼표로 구분된 보안 그룹 ID 목록 (예: "sg-abc,sg-def")
     * @param account "requester" 또는 "accepter" (기본값: "requester")
     */
    @GetMapping("/security-groups")
    public ResponseEntity<ApiResponse<List<SecurityGroupDetailDto>>> getSecurityGroups(
            @RequestParam String sgIds,
            @RequestParam(defaultValue = "requester") String account) {
        // 쉼표 구분 문자열을 List로 변환
        List<String> ids = Arrays.asList(sgIds.split(","));
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.getSecurityGroupDetails(ids, account)));
    }
}
