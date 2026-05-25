package com.company.vpc.util;

/**
 * CIDR 대역 유틸리티 클래스.
 *
 * <p>AWS VPC Peering 조건 검증에 사용된다.
 * AWS는 양측 VPC의 CIDR이 겹치는 경우 Peering을 허용하지 않으므로
 * AWS API 호출 전에 반드시 {@link #isOverlapping}으로 사전 검증한다.
 *
 * <p>인스턴스 생성 불가 — 정적 메서드만 제공한다.
 */
public class CidrUtils {

    /** 유틸리티 클래스 — 인스턴스 생성 차단 */
    private CidrUtils() {}

    /**
     * 두 CIDR 대역이 겹치는지 확인한다.
     *
     * <p>겹침 조건 (반구간 비교):
     * {@code net1 <= broadcast2 AND net2 <= broadcast1}
     * 이 조건이 참이면 두 대역이 하나 이상의 IP를 공유한다.
     *
     * @param cidr1 첫 번째 CIDR (예: 10.1.0.0/16)
     * @param cidr2 두 번째 CIDR (예: 10.2.0.0/16)
     * @return 겹치면 {@code true}, 완전히 분리되면 {@code false}
     */
    public static boolean isOverlapping(String cidr1, String cidr2) {
        long[] r1 = toRange(cidr1);
        long[] r2 = toRange(cidr2);
        return r1[0] <= r2[1] && r2[0] <= r1[1];
    }

    /**
     * CIDR 문자열을 [네트워크 주소, 브로드캐스트 주소] long 배열로 변환한다.
     *
     * <p>long 타입을 사용하는 이유: Java int는 부호 있는 32비트이므로
     * 128.x.x.x 이상 주소가 음수로 표현되어 비교 오류가 발생한다.
     * 0xFFFFFFFFL 마스킹으로 unsigned 32비트로 처리한다.
     *
     * @param cidr CIDR 표기법 문자열 (예: 10.0.0.0/8)
     * @return [0] = 네트워크 주소, [1] = 브로드캐스트 주소 (long)
     */
    private static long[] toRange(String cidr) {
        String[] parts = cidr.trim().split("/");
        int prefix = Integer.parseInt(parts[1]);
        long ip   = toIpLong(parts[0]);
        // prefix가 0이면 모든 비트가 0인 마스크(슈퍼넷), 그 외에는 상위 prefix 비트만 1
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long net  = ip & mask;
        long bcast = net | (~mask & 0xFFFFFFFFL);
        return new long[]{net, bcast};
    }

    /**
     * IPv4 점-십진수 문자열을 unsigned long으로 변환한다.
     *
     * @param ip IPv4 주소 (예: "10.0.0.1")
     * @return unsigned 32비트 정수 표현
     */
    private static long toIpLong(String ip) {
        String[] octs = ip.split("\\.");
        long v = 0;
        for (String o : octs) v = (v << 8) | Long.parseLong(o);
        return v;
    }
}
