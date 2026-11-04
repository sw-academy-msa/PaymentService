package com.msa.payment.domain.payment.service;

import com.msa.payment.domain.payment.dto.response.*;

/** 짧은 DB 트랜잭션으로 임대·결과·재시도를 저장합니다. 외부 HTTP는 이 경계 밖입니다. */
public interface PaymentWorkStore {
    PaymentWork claim();

    void complete(PaymentWork work, ProviderReply reply);
}
