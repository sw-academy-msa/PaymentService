package com.msa.payment.domain.payment.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 구매 주문과 광고의 공급자 식별자 및 멱등 키를 분리하는 내부 요청입니다. */
public record TossPaymentRequest(
        String tossOrderId,
        long amount,
        Instant expiresAt,
        String paymentKey,
        boolean firstApproval,
        boolean approvalRequested,
        boolean cancellationRequested,
        String approvalIdempotencyKey,
        String cancellationIdempotencyKey,
        String cancellationReason) {

    /** 기존 구매 결제의 공급자 요청 계약을 유지합니다. */
    public static TossPaymentRequest purchase(PaymentWork work) {
        return new TossPaymentRequest(
                work.tossOrderId(),
                work.amount(),
                work.expiresAt(),
                work.paymentKey(),
                work.firstApproval(),
                work.approvalRequested(),
                work.cancellationRequested(),
                "stockdrop-approve-" + work.attemptId(),
                "stockdrop-cancel-" + work.attemptId(),
                "주문 처리 보상 취소");
    }

    /** 광고에는 구매 주문 UUID나 판매자 입력 취소 사유를 사용하지 않습니다. */
    public static TossPaymentRequest advertising(
            UUID paymentId,
            long amount,
            Instant expiresAt,
            String paymentKey,
            boolean firstApproval,
            boolean approvalRequested,
            boolean cancellationRequested) {
        if (paymentId == null || amount < 1000 || expiresAt == null) {
            throw new IllegalArgumentException("광고 결제 원본을 확인해 주세요.");
        }
        return new TossPaymentRequest(
                "AD-" + paymentId,
                amount,
                expiresAt,
                paymentKey,
                firstApproval,
                approvalRequested,
                cancellationRequested,
                "stockdrop-ad-approve-" + paymentId,
                "stockdrop-ad-cancel-" + paymentId,
                "상품 판매 심사 반려");
    }

    @Override
    public String toString() {
        return "TossPaymentRequest[REDACTED]";
    }
}
