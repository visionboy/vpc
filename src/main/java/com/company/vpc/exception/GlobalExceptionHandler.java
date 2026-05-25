package com.company.vpc.exception;

import com.company.vpc.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전역 예외 처리기.
 *
 * <p>컨트롤러에서 발생하는 모든 예외를 한 곳에서 처리해 일관된 {@link ApiResponse} 형태로 반환한다.
 * 컨트롤러·서비스에서는 try-catch 없이 예외를 throw만 하면 된다.
 *
 * <p>처리 우선순위:
 * <ol>
 *   <li>{@link BusinessException} — 비즈니스 규칙 위반 (400 or 404)</li>
 *   <li>{@link MethodArgumentNotValidException} — @Valid 검증 실패 (400)</li>
 *   <li>{@link Exception} — 그 외 모든 예외 (500)</li>
 * </ol>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외 처리.
     *
     * <p>에러 코드에 따라 HTTP 상태를 결정한다:
     * PEERING_NOT_FOUND, SNAPSHOT_NOT_FOUND, MIGRATION_NOT_FOUND → 404, 나머지 → 400.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception [{}]: {}", e.getErrorCode(), e.getMessage());
        HttpStatus status = e.getErrorCode() == ErrorCode.PEERING_NOT_FOUND
                || e.getErrorCode() == ErrorCode.SNAPSHOT_NOT_FOUND
                || e.getErrorCode() == ErrorCode.MIGRATION_NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * Bean Validation 실패 처리 ({@code @Valid} 어노테이션).
     *
     * <p>필드별 오류 메시지를 Map으로 수집하지만 응답 body에는 포함하지 않고 로그로만 남긴다.
     * 클라이언트에는 단순 실패 메시지만 반환한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "유효하지 않은 값입니다."
                ));
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("입력값 검증에 실패했습니다."));
    }

    /**
     * 예상치 못한 모든 예외 처리 — 스택 트레이스를 ERROR 레벨로 남긴다.
     * 클라이언트에는 내부 구조가 노출되지 않도록 일반 메시지만 반환한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail("서버 내부 오류가 발생했습니다."));
    }
}
