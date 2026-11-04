package com.msa.payment.domain.payment.service;

import com.msa.payment.domain.payment.entity.PaymentAttempt;

/** 원본 상태와 결과 Outbox를 같은 트랜잭션으로 저장합니다. */
public interface PaymentResultOutboxService {
    void record(PaymentAttempt attempt);
}
