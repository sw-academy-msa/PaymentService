package com.msa.payment.domain.payment.client;

import com.msa.payment.domain.payment.dto.response.*;

/** 토스 테스트 승인·조회·Saga 전액 보상만 수행하고 주문·재고는 변경하지 않습니다. */
public interface TossClient {
    ProviderReply reconcile(PaymentWork work);

    /** 광고도 동일한 승인 및 조회 검증을 사용하며 구매 주문 파서를 통과하지 않습니다. */
    ProviderReply reconcile(TossPaymentRequest request);
}
