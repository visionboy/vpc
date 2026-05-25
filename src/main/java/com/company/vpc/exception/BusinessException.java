package com.company.vpc.exception;

import lombok.Getter;

/**
 * 비즈니스 규칙 위반 시 발생하는 예외.
 *
 * <p>서비스 계층에서 throw하고 {@link GlobalExceptionHandler}가 HTTP 응답으로 변환한다.
 * 컨트롤러에서 직접 try-catch하지 않는다.
 *
 * <p>에러 코드별 HTTP 상태는 {@code GlobalExceptionHandler}에서 일괄 결정된다:
 * <ul>
 *   <li>PEERING_NOT_FOUND, SNAPSHOT_NOT_FOUND, MIGRATION_NOT_FOUND → 404</li>
 *   <li>그 외 → 400</li>
 * </ul>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 에러 유형 식별자 — HTTP 상태 코드 결정 및 로그에 활용 */
    private final ErrorCode errorCode;

    /**
     * 에러 코드만으로 생성 — 메시지는 {@link ErrorCode#getMessage()} 사용.
     *
     * @param errorCode 에러 유형
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 에러 코드 + 추가 상세 메시지로 생성.
     * 예: "CIDR_OVERLAP — 10.0.0.0/16 ↔ 10.0.1.0/24"
     *
     * @param errorCode 에러 유형
     * @param detail    추가 상세 정보 (CIDR 값, ID 등)
     */
    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + " — " + detail);
        this.errorCode = errorCode;
    }
}
