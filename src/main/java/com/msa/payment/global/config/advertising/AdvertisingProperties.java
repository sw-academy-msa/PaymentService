package com.msa.payment.global.config.advertising;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** 신규 접수와 금융 복구 플래그를 분리해 기능 중단 중에도 결제를 복구합니다. */
@ConfigurationProperties("payment.advertising")
public record AdvertisingProperties(
        boolean enabled,
        boolean consumerEnabled,
        boolean processingEnabled,
        URI successUrl,
        URI failUrl) {
    @Override
    public String toString() {
        return "AdvertisingProperties[REDACTED]";
    }
}
