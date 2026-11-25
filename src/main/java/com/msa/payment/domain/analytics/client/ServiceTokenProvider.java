package com.msa.payment.domain.analytics.client;

/** Account 기존 client_credentials 토큰을 캐시하며 만료 토큰은 반환하지 않습니다. */
public interface ServiceTokenProvider {
    String token();

    void invalidate();
}
