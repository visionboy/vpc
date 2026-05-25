package com.company.vpc.service;

import com.company.vpc.domain.PeeringHistory;
import com.company.vpc.domain.enums.CspType;
import com.company.vpc.dto.request.PeeringCreateRequest;
import com.company.vpc.dto.response.PeeringResultDto;

import java.util.List;
import java.util.Map;

/**
 * CSP(Cloud Service Provider)별 VPC Peering 연산 전략 인터페이스.
 *
 * <p>Strategy 패턴으로 CSP별 구현을 분리한다.
 * {@link PeeringManagementService}는 이 인터페이스에만 의존하므로
 * 새 CSP 지원 추가 시 이 인터페이스를 구현한 {@code @Service}를 만들기만 하면 된다.
 * (Open/Closed Principle)
 *
 * <p>현재 구현체: {@link com.company.vpc.service.aws.AwsPeeringServiceImpl} (AWS)
 *
 * <p>CSP 라우팅 방식:
 * <pre>
 * List&lt;CspPeeringService&gt; 전체 목록 → getCspType() 일치 항목 선택 → 해당 구현체 사용
 * </pre>
 */
public interface CspPeeringService {

    /**
     * 이 구현체가 처리하는 CSP 유형 반환.
     * {@link PeeringManagementService}에서 구현체 선택 기준으로 사용된다.
     */
    CspType getCspType();

    /**
     * VPC Peering 생성: Peering 요청 → 수락 → 라우팅 추가 → SG 규칙 추가 (5단계).
     *
     * <p>중간 단계에서 실패하면 보상 트랜잭션(Compensating Transaction)으로
     * 이미 생성된 AWS 리소스를 역순으로 정리한 뒤 예외를 throw한다.
     *
     * @param request 생성 요청 DTO
     * @return Peering Connection ID를 포함한 결과 DTO
     * @throws com.company.vpc.exception.BusinessException AWS API 오류 시
     */
    PeeringResultDto createPeering(PeeringCreateRequest request);

    /**
     * 삭제 전 네트워크 구성 스냅샷 수집.
     *
     * <p>라우팅 테이블·보안 그룹의 현재 상태를 JSON 직렬화해 반환한다.
     * 수집 실패는 경고 로그만 남기고 삭제를 계속 진행한다.
     *
     * @param history 삭제 대상 Peering 이력
     * @return key: SnapshotDataType.name() + 식별자, value: JSON 문자열
     */
    Map<String, String> captureSnapshots(PeeringHistory history);

    /**
     * 수락자 VPC 내 모든 EC2 인스턴스의 보안 그룹 상태를 JSON 배열로 수집.
     *
     * <p>Jumphost 이전 전·후 비교 기능에서 SECURITY_GROUP / SECURITY_GROUP_POST 스냅샷 저장에 사용된다.
     * 반환값은 {@code SecurityGroupDetailDto} 호환 JSON 배열로,
     * {@code inboundRules}·{@code outboundRules} 필드를 포함한다.
     *
     * <p>수집 실패 시 빈 배열({@code []})을 반환하며 예외를 전파하지 않는다.
     *
     * @param accepterVpcId 수락자 VPC ID
     * @return 보안 그룹 목록을 직렬화한 JSON 배열 문자열 (실패 시 "[]")
     */
    String captureVpcSecurityGroups(String accepterVpcId);

    /**
     * VPC Peering 삭제: SG 규칙 → 수락자 라우팅 → 요청자 라우팅 → Peering 연결 역순 정리.
     *
     * @param history 삭제 대상 Peering 이력
     * @throws com.company.vpc.exception.BusinessException AWS API 오류 시
     */
    void deletePeering(PeeringHistory history);
}
