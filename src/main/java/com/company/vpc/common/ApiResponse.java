package com.company.vpc.common;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모든 REST API의 공통 응답 래퍼.
 *
 * <p>클라이언트가 성공/실패 여부를 일관된 구조로 처리할 수 있도록
 * {@code success}, {@code message}, {@code data} 세 필드를 고정한다.
 *
 * <pre>
 * // 성공 응답 예시
 * ApiResponse.ok(data)
 * → { "success": true, "message": "SUCCESS", "data": {...} }
 *
 * // 실패 응답 예시
 * ApiResponse.fail("오류 메시지")
 * → { "success": false, "message": "오류 메시지", "data": null }
 * </pre>
 *
 * @param <T> 응답 데이터 타입
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiResponse<T> {

    /** 요청 처리 성공 여부 */
    private boolean success;

    /** 처리 결과 메시지 (성공: "SUCCESS", 실패: 오류 내용) */
    private String message;

    /** 실제 응답 데이터 (실패 시 null) */
    private T data;

    /**
     * 기본 성공 응답 — 메시지는 "SUCCESS"로 고정된다.
     *
     * @param data 응답 데이터
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "SUCCESS", data);
    }

    /**
     * 커스텀 메시지를 포함한 성공 응답.
     *
     * @param message 성공 메시지 (예: "VPC Peering이 성공적으로 생성되었습니다.")
     * @param data    응답 데이터
     */
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    /**
     * 실패 응답 — data는 항상 null이다.
     *
     * @param message 오류 메시지
     */
    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }

    /** 외부 직접 생성 차단 — 정적 팩토리 메서드만 사용한다. */
    private ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }
}
