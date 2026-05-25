package com.company.vpc.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 비즈니스 에러 코드 목록.
 *
 * <p>각 코드는 {@link BusinessException}에 담겨 {@link GlobalExceptionHandler}에서
 * HTTP 상태 코드와 응답 메시지로 변환된다.
 *
 * <p>에러 코드 추가 시 반드시 {@code GlobalExceptionHandler}의 HTTP 상태 결정 로직도 함께 확인한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    /** DB에 해당 ID의 Peering 이력이 없을 때 — HTTP 404 */
    PEERING_NOT_FOUND("존재하지 않는 VPC Peering 이력입니다."),

    /** 이미 DELETED 상태인 Peering에 삭제 요청이 들어올 때 — HTTP 400 */
    PEERING_ALREADY_DELETED("이미 삭제된 VPC Peering입니다."),

    /** Jumphost 이전 대상 Peering이 ACTIVE 상태가 아닐 때 — HTTP 400 */
    PEERING_NOT_ACTIVE("ACTIVE 상태의 VPC Peering만 이전할 수 있습니다."),

    /** AWS API 호출 도중 Peering 생성 5단계 중 하나라도 실패했을 때 — HTTP 400 */
    PEERING_CREATION_FAILED("VPC Peering 생성에 실패했습니다."),

    /** AWS API 호출 도중 Peering 삭제 역순 정리 중 실패했을 때 — HTTP 400 */
    PEERING_DELETION_FAILED("VPC Peering 삭제에 실패했습니다."),

    /** 스냅샷 단건 조회 시 해당 ID가 없을 때 — HTTP 404 */
    SNAPSHOT_NOT_FOUND("스냅샷 데이터를 찾을 수 없습니다."),

    /** Strategy 패턴에서 요청한 CspType에 해당하는 구현체가 없을 때 — HTTP 400 */
    UNSUPPORTED_CSP("지원하지 않는 CSP 유형입니다."),

    /** CIDR 형식이 잘못된 경우 (정규식 불일치) — HTTP 400 */
    INVALID_CIDR("유효하지 않은 CIDR 형식입니다."),

    /**
     * 요청자·수락자 VPC CIDR이 겹칠 때 — HTTP 400.
     * AWS VPC Peering은 양측 CIDR이 반드시 겹치지 않아야 한다.
     */
    CIDR_OVERLAP("요청자와 수락자의 CIDR 대역이 겹칩니다. VPC Peering은 CIDR이 겹치지 않아야 합니다."),

    /** DB에 해당 ID의 이전 이력이 없을 때 — HTTP 404 */
    MIGRATION_NOT_FOUND("존재하지 않는 이전 이력입니다."),

    /** 이미 완료 또는 롤백된 이전 이력에 재요청이 들어올 때 — HTTP 400 */
    MIGRATION_ALREADY_FINISHED("이미 완료 또는 롤백된 이전 이력입니다."),

    /** AWS SDK 호출 시 Ec2Exception/RdsException이 발생했을 때 — HTTP 400 */
    AWS_API_ERROR("AWS API 호출 중 오류가 발생했습니다.");

    /** 클라이언트에 전달될 한국어 에러 메시지 */
    private final String message;
}
