package com.company.vpc.dto.response;

import com.company.vpc.domain.NetworkSnapshot;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 네트워크 스냅샷 응답 DTO.
 *
 * <p>Peering 삭제 전 캡처한 라우팅 테이블·보안 그룹 JSON 데이터를 담는다.
 * Entity → DTO 변환은 반드시 {@link #from} 정적 팩토리 메서드만 사용한다.
 */
@Getter
@Builder
public class NetworkSnapshotResponse {

    /** DB PK */
    private Long id;

    /** 스냅샷 종류 ("ROUTE_TABLE" or "SECURITY_GROUP") */
    private String dataType;

    /** AWS API 응답을 직렬화한 JSON 문자열 — 화면에서 pretty print로 표시 */
    private String snapshotData;

    /** 스냅샷 캡처 일시 */
    private LocalDateTime createdAt;

    /**
     * {@link NetworkSnapshot} Entity → {@link NetworkSnapshotResponse} DTO 변환.
     *
     * @param s 변환할 스냅샷 Entity
     */
    public static NetworkSnapshotResponse from(NetworkSnapshot s) {
        return NetworkSnapshotResponse.builder()
                .id(s.getId())
                .dataType(s.getDataType().name())
                .snapshotData(s.getSnapshotData())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
