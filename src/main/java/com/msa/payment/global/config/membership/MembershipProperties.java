package com.msa.payment.global.config.membership;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/** 신규 가입과 갱신 및 불명 복구를 독립적으로 제어합니다. */
@ConfigurationProperties("membership")
public record MembershipProperties(
        @DefaultValue("false") boolean apiEnabled,
        @DefaultValue("false") boolean renewalEnabled,
        @DefaultValue("false") boolean recoveryEnabled,
        @DefaultValue("false") boolean consumerEnabled,
        @DefaultValue("4900") long amount,
        @DefaultValue("monthly-v1") String planVersion,
        @DefaultValue("recurring-v1") String termsVersion,
        @DefaultValue("https://stockdrop-app.vercel.app/#/membership/billing/success")
                URI successUrl,
        @DefaultValue("https://stockdrop-app.vercel.app/#/membership/billing/fail") URI failUrl,
        @DefaultValue("v1") String providerKeyVersion) {
    @Override
    public String toString() {
        return "MembershipProperties[REDACTED]";
    }
}
