package com.msa.payment.global.config.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** MP 비밀값을 설정 객체의 문자열 표현에서 숨깁니다. */
@ConfigurationProperties("payment.ga")
public record GaProperties(
        boolean enabled,
        String measurementId,
        String apiSecret,
        URI baseUrl,
        boolean allowInsecureLocal) {
    @Override
    public String toString() {
        return "GaProperties[REDACTED]";
    }
}
