package com.msa.payment.domain.analytics.dto.response;

import java.time.Instant;
import java.util.UUID;

/** HTTP 작업에만 전달되는 임대 스냅샷으로 원문을 숨깁니다. */
public record GaWork(
        UUID orderId, String kind, String payload, long amount, UUID token, Instant occurredAt) {
    @Override
    public String toString() {
        return "GaWork[REDACTED]";
    }
}
