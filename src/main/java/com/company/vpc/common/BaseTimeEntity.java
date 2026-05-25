package com.company.vpc.common;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 생성일시·수정일시를 공통으로 관리하는 부모 클래스.
 *
 * <p>MyBatis 전환 이후 JPA Auditing({@code @CreatedDate}, {@code @LastModifiedDate})을 사용하지 않으므로
 * 타임스탬프는 서비스 계층에서 {@code LocalDateTime.now()}로 직접 설정한다.
 *
 * <p>모든 도메인 엔티티(PeeringHistory, NetworkSnapshot)는 이 클래스를 상속해야 한다.
 */
@Getter
@Setter
public abstract class BaseTimeEntity {

    /** 레코드 최초 생성 일시 — INSERT 전 서비스에서 설정 */
    protected LocalDateTime createdAt;

    /** 레코드 최종 수정 일시 — UPDATE 전 서비스에서 설정 */
    protected LocalDateTime updatedAt;
}
