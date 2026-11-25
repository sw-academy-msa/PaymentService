package com.msa.payment.domain.analytics.service;

import com.msa.payment.domain.analytics.dto.response.GaWork;
import com.msa.payment.domain.payment.entity.PaymentAttempt;

/** 결제 원본과 같은 TX에 전송 의도를 저장하고 별도 임대로 전송합니다. */
public interface GaDispatchStore {
    void record(PaymentAttempt attempt);

    GaWork claim();

    void complete(GaWork work, String status);

    void purge();
}
