package com.msa.payment.domain.membership.dto.request;

/** DB에 먼저 고정한 주문번호와 멱등 키를 공급자 호출에 전달하는 내부 요청입니다. */
public record BillingCharge(
        String orderId, long amount, String customerKey, String idempotencyKey) {
    public BillingCharge {
        if (orderId == null
                || !orderId.matches("[A-Za-z0-9_=-]{6,64}")
                || amount < 100
                || customerKey == null
                || customerKey.length() < 22
                || customerKey.length() > 50
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 300)
            throw new IllegalArgumentException("빌링 청구 계약이 올바르지 않습니다.");
    }

    @Override
    public String toString() {
        return "BillingCharge[REDACTED]";
    }
}
