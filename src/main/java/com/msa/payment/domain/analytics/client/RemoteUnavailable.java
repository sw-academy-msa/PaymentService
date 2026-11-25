package com.msa.payment.domain.analytics.client;

import com.msa.payment.domain.payment.exception.PaymentErrorCode;
import com.msa.payment.domain.payment.exception.PaymentException;

/** 분석 의존 장애의 원문과 토큰을 숨기는 재시도 예외입니다. */
public class RemoteUnavailable extends PaymentException {
    public RemoteUnavailable() {
        super(PaymentErrorCode.SERVICE_UNAVAILABLE);
    }
}
