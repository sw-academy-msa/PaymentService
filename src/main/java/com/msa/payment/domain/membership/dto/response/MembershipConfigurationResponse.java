package com.msa.payment.domain.membership.dto.response;

import java.net.URI;

/** 공개 SDK 키와 동의 대상 가격 및 서버 고정 redirect만 제공합니다. */
public record MembershipConfigurationResponse(
        String clientKey,
        String planVersion,
        long amount,
        String currency,
        String termsVersion,
        URI successUrl,
        URI failUrl) {
    @Override
    public String toString() {
        return "MembershipConfigurationResponse[REDACTED]";
    }
}
