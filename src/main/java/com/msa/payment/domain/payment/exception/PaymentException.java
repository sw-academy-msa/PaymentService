package com.msa.payment.domain.payment.exception;

import com.msa.payment.global.error.BusinessException;

/** 결제 업무 예외를 공통 처리기로 전달합니다. 이 기반 타입 전체를 Kafka 재시도 제외 대상으로 등록하지 않습니다. */
public class PaymentException extends BusinessException {
    public PaymentException(PaymentErrorCode errorCode) {
        super(errorCode);
    }
}
