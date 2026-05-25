package com.company.vpc.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 라우팅 테이블 응답 DTO.
 *
 * <p>VPC ID 조회 시 드롭다운 목록에 표시되는 데이터이다.
 * Main 라우팅 테이블이 맨 앞에 정렬되어 반환되며,
 * 프론트엔드에서 {@code main=true}인 항목을 기본 선택으로 표시한다.
 */
@Getter
@Builder
public class RouteTableDto {

    /** 라우팅 테이블 ID (예: rtb-0abc123) */
    private String routeTableId;

    /** 이 라우팅 테이블이 속한 VPC ID */
    private String vpcId;

    /** Main 라우팅 테이블 여부 — association 중 main=true인 항목 존재 시 true */
    private boolean main;

    /** AWS Name 태그 값 — 없으면 빈 문자열 */
    private String name;

    /** 이 라우팅 테이블에 명시적으로 연결된 서브넷 ID 목록 (Main 라우팅 테이블은 보통 비어있음) */
    private List<String> subnetIds;
}
