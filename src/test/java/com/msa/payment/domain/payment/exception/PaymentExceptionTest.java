package com.msa.payment.domain.payment.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.msa.payment.domain.payment.InvalidPaymentCommandException;

import org.junit.jupiter.api.Test;

/** 도메인 상속 구조를 바꿔도 DLT 예외 메타데이터와 원문 비노출 규칙을 유지하는지 검증합니다. */
class PaymentExceptionTest {
    @Test
    void permanentCommandFailureKeepsExistingTypeAndMessage() {
        var exception = new InvalidPaymentCommandException();

        assertThat(exception).isInstanceOf(PaymentException.class);
        assertThat(exception.getClass().getName())
                .isEqualTo("com.msa.payment.domain.payment.InvalidPaymentCommandException");
        assertThat(exception.getMessage()).isEqualTo("PAYMENT_COMMAND_INVALID");
        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.INVALID_COMMAND);
        assertThat(exception.getCause()).isNull();
    }
}
