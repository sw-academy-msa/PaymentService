package com.msa.payment.global.config.membership;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 암호화 키는 값이나 기본 record 문자열 표현으로 외부에 노출하지 않습니다. */
@ConfigurationProperties("membership.crypto")
public record MembershipCryptoProperties(String dataKeyV1) {
    @Override
    public String toString() {
        return "MembershipCryptoProperties[REDACTED]";
    }
}
