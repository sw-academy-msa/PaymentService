package com.msa.payment.domain.payment.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** DB 트랜잭션 밖에서 토스를 호출할 내부 작업 DTO입니다. HTTP 응답으로 반환하지 않습니다. */
@Builder
public record PaymentWork(
        UUID attemptId,
        UUID orderId,
        long amount,
        Instant expiresAt,
        String paymentKey,
        UUID leaseToken,
        boolean firstApproval,
        boolean approvalRequested,
        boolean cancellationRequested) {
    /** 빌더에 담긴 개인정보·결제키·토큰·이벤트 본문을 로그에 노출하지 않습니다. */
    public static class PaymentWorkBuilder {
        @Override
        public String toString() {
            return "PaymentWorkBuilder[REDACTED]";
        }
    }

    public String tossOrderId() {
        return "SD-" + orderId;
    }

    @Override
    public String toString() {
        return "PaymentWork[attemptId=" + attemptId + "]";
    }
}
