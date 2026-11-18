package com.msa.payment.global.config.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 테스트 키만 허용하는 공급자 설정입니다. 기본 record 출력으로 시크릿이 노출되지 않게 합니다. */
@ConfigurationProperties("payment.toss")
public record TossProperties(
        boolean enabled,
        String secretKey,
        String clientKey,
        URI baseUrl,
        Duration requestTimeout,
        boolean allowInsecureLocal) {
    @Override
    public String toString() {
        return "TossProperties[enabled=" + enabled + "]";
    }
}
