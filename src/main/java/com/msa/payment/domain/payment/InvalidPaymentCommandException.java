package com.msa.payment.domain.payment;

import com.msa.payment.domain.payment.exception.PaymentErrorCode;
import com.msa.payment.domain.payment.exception.PaymentException;

/**
 * 영구 계약 오류만 DLT로 격리하며 원문·키·서명 정보를 예외에 포함하지 않습니다. 기존 타입·패키지·메시지는 Kafka 재시도 분류와 DLT 예외 헤더 호환성을 위해
 * 유지합니다.
 */
public class InvalidPaymentCommandException extends PaymentException {
    public InvalidPaymentCommandException() {
        super(PaymentErrorCode.INVALID_COMMAND);
    }
}
