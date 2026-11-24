package com.msa.payment.domain.membership.dto.response;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** customerKey는 본인 SDK 인증에만 제공하며 로그와 구독 조회에서는 제외합니다. */
public record MembershipSessionResponse(
        UUID sessionId,
        String customerKey,
        String clientKey,
        Instant expiresAt,
        URI successUrl,
        URI failUrl) {
    @Override
    public String toString() {
        return "MembershipSessionResponse[REDACTED]";
    }
}
