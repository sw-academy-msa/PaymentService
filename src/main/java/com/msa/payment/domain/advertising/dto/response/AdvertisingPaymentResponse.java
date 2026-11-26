package com.msa.payment.domain.advertising.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 외부에 금융 상태만 반환하고 공급자 결제키와 임대 정보는 제외합니다. */
public record AdvertisingPaymentResponse(
        UUID paymentId,
        UUID applicationId,
        UUID submissionId,
        UUID productId,
        long amount,
        String currency,
        String paymentStatus,
        boolean cancellationRequested,
        Instant approvedAt,
        Instant canceledAt,
        Instant expiresAt,
        long resultVersion) {}
