package com.msa.payment.domain.payment.client;

import com.msa.payment.domain.payment.dto.response.*;

/** 토스 테스트 승인·조회·Saga 전액 보상만 수행하고 주문·재고는 변경하지 않습니다. */
public interface TossClient {
    ProviderReply reconcile(PaymentWork work);
}
