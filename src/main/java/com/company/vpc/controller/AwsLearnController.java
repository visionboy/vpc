package com.company.vpc.controller;

import com.company.vpc.common.ApiResponse;
import com.company.vpc.dto.request.AwsLearnRoleRequest;
import com.company.vpc.dto.request.AwsLearnVpcRequest;
import com.company.vpc.dto.response.AwsLearnRoleResourceResponse;
import com.company.vpc.dto.response.VpcInfoDto;
import com.company.vpc.service.AwsLearnService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * AWS 학습용 리소스 조회 REST 컨트롤러.
 *
 * <p>기본 URL: {@code /api/v1/learn}
 *
 * <p>지원 엔드포인트:
 * <ul>
 *   <li>{@code POST /vpcs} — Access Key + Secret Key로 VPC 목록 조회</li>
 *   <li>{@code POST /vpcs-by-role} — Role ARN AssumeRole로 VPC 목록 조회</li>
 *   <li>{@code POST /resources-by-role} — Role ARN AssumeRole로 VPC + EC2 + RDS 통합 조회</li>
 * </ul>
 *
 * <p>자격증명은 애플리케이션에 저장되지 않으며 요청 처리 후 즉시 폐기된다.
 * 학습·테스트 목적 전용 엔드포인트이다.
 */
@RestController
@RequestMapping("/api/v1/learn")
@RequiredArgsConstructor
@Validated
public class AwsLearnController {

    /** VPC 목록 조회 서비스 */
    private final AwsLearnService learnService;

    /**
     * 직접 입력된 AWS 자격증명으로 VPC 목록 조회.
     *
     * <p>요청 바디에 accessKey, secretKey, region을 포함해야 한다.
     * 인증 오류 또는 권한 부족 시 AWS API 오류 메시지가 반환된다.
     *
     * @param request accessKey, secretKey, region이 담긴 요청 DTO
     * @return 해당 리전의 VPC 목록 (없으면 빈 배열)
     */
    @PostMapping("/vpcs")
    public ResponseEntity<ApiResponse<List<VpcInfoDto>>> listVpcs(
            @RequestBody @Valid AwsLearnVpcRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(learnService.listVpcs(request)));
    }

    /**
     * STS AssumeRole 방식으로 VPC 목록 조회.
     *
     * <p>호출자(accessKey + secretKey)가 지정된 roleArn으로 AssumeRole을 수행한 뒤
     * 발급된 임시 자격증명으로 VPC 목록을 조회한다.
     * ExternalId와 sessionName은 선택 항목이다.
     *
     * @param request accessKey, secretKey, roleArn, region (+ 선택: sessionName, externalId)
     * @return 해당 리전의 VPC 목록 (없으면 빈 배열)
     */
    @PostMapping("/vpcs-by-role")
    public ResponseEntity<ApiResponse<List<VpcInfoDto>>> listVpcsByRole(
            @RequestBody @Valid AwsLearnRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(learnService.listVpcsByRole(request)));
    }

    /**
     * STS AssumeRole 방식으로 VPC + EC2 + RDS 통합 조회.
     *
     * <p>AssumeRole로 획득한 임시 자격증명으로 리전 내 모든 VPC, EC2 인스턴스(terminated 제외),
     * RDS 인스턴스를 한 번에 조회하여 반환한다.
     * RDS 조회 권한이 없으면 rdsError 필드에 메시지를 담고 VPC·EC2는 정상 반환한다.
     *
     * @param request accessKey, secretKey, roleArn, region (+ 선택: sessionName, externalId)
     * @return VPC + EC2 + RDS 통합 응답
     */
    @PostMapping("/resources-by-role")
    public ResponseEntity<ApiResponse<AwsLearnRoleResourceResponse>> getResourcesByRole(
            @RequestBody @Valid AwsLearnRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(learnService.getResourcesByRole(request)));
    }
}
