package com.msa.payment.domain.payment.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;

import org.junit.jupiter.api.Test;

/** 결과 불명을 실패로 바꾸지 않고 빌더에서도 결제키를 노출하지 않는지 검증합니다. */
class PaymentBuilderTest {
    @Test
    void unknownResultAndRedactedBuilderOutputArePreserved() {
        var unknown = ProviderReply.unknown("fixture-unknown");
        assertThat(unknown.status()).isEqualTo(Status.UNKNOWN);
        assertThat(unknown.failureCode()).isEqualTo("fixture-unknown");
        assertThat(unknown.approvedAt()).isNull();
        assertThat(unknown.paymentKey()).isNull();
        assertThat(PaymentWork.builder().paymentKey("fixture-payment-key").toString())
                .isEqualTo("PaymentWorkBuilder[REDACTED]");
        assertThat(ProviderReply.builder().paymentKey("fixture-payment-key").toString())
                .isEqualTo("ProviderReplyBuilder[REDACTED]");
    }
}
