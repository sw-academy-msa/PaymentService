package com.msa.payment.global.config.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 내부 호출 대상 서비스 ID와 Account 서비스 토큰 자격을 묶습니다. 비밀값은 문자열에 노출하지 않습니다. */
@ConfigurationProperties("payment.clients")
public record ClientProperties(
        String scheme, String account, String ai, String clientSecret, String serviceSubject) {
    public ClientProperties {
        scheme = scheme == null || scheme.isBlank() ? "https" : scheme;
    }

    @Override
    public String toString() {
        return "ClientProperties[REDACTED]";
    }
}
