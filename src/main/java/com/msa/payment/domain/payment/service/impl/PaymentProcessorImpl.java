package com.msa.payment.domain.payment.service.impl;

import com.msa.payment.domain.payment.client.TossClient;
import com.msa.payment.domain.payment.dto.response.ProviderReply;
import com.msa.payment.domain.payment.service.*;

import org.springframework.stereotype.Service;

/** 토스 네트워크 호출 중 DB 트랜잭션·행 잠금을 유지하지 않습니다. 결과 불명은 영속 재조회합니다. */
@Service
public class PaymentProcessorImpl implements PaymentProcessor {
    private final PaymentWorkStore store;
    private final TossClient toss;

    public PaymentProcessorImpl(PaymentWorkStore store, TossClient toss) {
        this.store = store;
        this.toss = toss;
    }

    public boolean processNext() {
        var work = store.claim();
        if (work == null) return false;
        ProviderReply reply;
        try {
            reply = toss.reconcile(work);
        } catch (RuntimeException failure) {
            reply = ProviderReply.unknown("PROVIDER_RESULT_UNKNOWN");
        }
        store.complete(work, reply);
        return true;
    }
}
