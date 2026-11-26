package com.msa.payment.domain.advertising.dto.response;

import com.msa.payment.domain.payment.dto.response.TossPaymentRequest;

import java.util.UUID;

/** 외부 HTTP와 DB 임대 완료를 연결하는 비공개 작업입니다. */
public record AdvertisingWork(UUID paymentId, UUID leaseToken, TossPaymentRequest request) {
    @Override
    public String toString() {
        return "AdvertisingWork[REDACTED]";
    }
}
