package com.company.vpc.common;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * MyBatis 페이지네이션 결과를 담는 공통 래퍼.
 *
 * <p>Spring Data JPA의 {@code Page<T>}를 대체한다.
 * MyBatis는 자동 페이징을 지원하지 않으므로 Mapper에서 LIMIT/OFFSET으로 content를 조회하고,
 * 별도 COUNT 쿼리 결과를 넘겨 {@link #of} 팩토리로 생성한다.
 *
 * <pre>
 * // 사용 예시
 * List&lt;T&gt; content = mapper.findAll(offset, size);
 * long total = mapper.countAll();
 * PageResult&lt;T&gt; result = PageResult.of(content, page, size, total);
 * </pre>
 *
 * @param <T> 페이지 내 항목 타입
 */
@Getter
@Builder
public class PageResult<T> {

    /** 현재 페이지에 해당하는 항목 목록 */
    private List<T> content;

    /** 현재 페이지 번호 (0-based) */
    private int page;

    /** 페이지당 항목 수 */
    private int size;

    /** 전체 항목 수 */
    private long totalElements;

    /** 전체 페이지 수 */
    private int totalPages;

    /**
     * 페이지 결과 생성 팩토리.
     *
     * @param content 현재 페이지 데이터 목록
     * @param page    현재 페이지 번호 (0-based)
     * @param size    페이지당 항목 수
     * @param total   전체 항목 수 (COUNT 쿼리 결과)
     */
    public static <T> PageResult<T> of(List<T> content, int page, int size, long total) {
        // size가 0이면 0으로, 아니면 올림 나눗셈으로 총 페이지 수 계산
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return PageResult.<T>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(total)
                .totalPages(totalPages)
                .build();
    }
}
