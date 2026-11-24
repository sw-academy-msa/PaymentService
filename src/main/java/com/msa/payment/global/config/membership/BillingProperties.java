package com.msa.payment.global.config.membership;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 빌링 전용 개별 테스트 키와 긴 승인 제한시간을 일반 결제 설정과 분리합니다. */
@ConfigurationProperties("membership.billing")
public record BillingProperties(
        boolean enabled,
        String clientKey,
        String secretKey,
        URI baseUrl,
        Duration requestTimeout,
        boolean allowInsecureLocal) {
    public BillingProperties {
        if (baseUrl == null) baseUrl = URI.create("https://api.tosspayments.com");
        if (requestTimeout == null) requestTimeout = Duration.ofSeconds(65);
    }

    @Override
    public String toString() {
        return "BillingProperties[REDACTED]";
    }
}
