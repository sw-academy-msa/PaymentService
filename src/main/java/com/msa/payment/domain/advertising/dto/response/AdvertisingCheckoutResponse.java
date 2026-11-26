package com.msa.payment.domain.advertising.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 판매자 본인의 결제 원본과 고정 귀환 주소 및 공개 테스트 키만 제공합니다. */
public record AdvertisingCheckoutResponse(
        UUID paymentId,
        UUID applicationId,
        String tossOrderId,
        long amount,
        String currency,
        String clientKey,
        Instant expiresAt,
        String successUrl,
        String failUrl,
        String state) {
    @Override
    public String toString() {
        return "AdvertisingCheckoutResponse[REDACTED]";
    }
}
