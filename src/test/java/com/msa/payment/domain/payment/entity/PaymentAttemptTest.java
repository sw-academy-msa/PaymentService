package com.msa.payment.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** 빌더가 승인 전 상태와 최초 복구 일정을 보존하는지 검증합니다. */
class PaymentAttemptTest {
    @Test
    void builderKeepsPendingStateAndDoesNotStartConfirmation() {
        var now = Instant.parse("2030-01-01T00:00:00Z");
        var expires = now.plusSeconds(300);
        var id = UUID.randomUUID();
        var order = UUID.randomUUID();
        var attempt =
                PaymentAttempt.builder()
                        .id(id)
                        .order(order)
                        .amount(6000)
                        .expiresAt(expires)
                        .now(now)
                        .build();

        assertThat(attempt.id).isEqualTo(id);
        assertThat(attempt.orderId).isEqualTo(order);
        assertThat(attempt.amount).isEqualTo(6000);
        assertThat(attempt.expiresAt).isEqualTo(expires);
        assertThat(attempt.createdAt).isEqualTo(now);
        assertThat(attempt.nextAttemptAt).isEqualTo(now);
        assertThat(attempt.status).isEqualTo(PaymentAttempt.Status.PENDING);
        assertThat(attempt.approvalRequested).isFalse();
        assertThat(attempt.cancellationRequested).isFalse();
        assertThat(attempt.confirmationStarted).isFalse();
        assertThat(attempt.resultVersion).isZero();
        assertThat(attempt.leaseToken).isNull();
        assertThat(attempt.paymentKey).isNull();
    }
}
