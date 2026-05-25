package com.company.vpc.domain.enums;

/**
 * 지원 대상 CSP(Cloud Service Provider) 유형.
 *
 * <p>Strategy 패턴({@link com.company.vpc.service.CspPeeringService})으로 CSP별 구현체를 분리하므로
 * 신규 CSP 추가 시 이 Enum에 값을 추가하고 해당 {@code @Service} 구현체를 만들면 된다.
 *
 * <p>현재 구현 완료: {@code AWS}
 * 나머지는 추후 확장 예정이며 요청 시 {@link com.company.vpc.exception.ErrorCode#UNSUPPORTED_CSP}가 발생한다.
 */
public enum CspType {

    /** Amazon Web Services — 현재 유일하게 구현된 CSP */
    AWS,

    /** AWS China (중국 리전, 별도 파티션) — 추후 확장 예정 */
    AWS_CHINA,

    /** Microsoft Azure — 추후 확장 예정 */
    AZURE,

    /** Google Cloud Platform — 추후 확장 예정 */
    GCP,

    /** Naver Cloud Platform — 추후 확장 예정 */
    NCP,

    /** Oracle Cloud Infrastructure — 추후 확장 예정 */
    OCI,

    /** Alibaba Cloud — 추후 확장 예정 */
    ALIBABA
}
