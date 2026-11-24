package com.msa.payment.domain.membership.client;

import com.msa.payment.domain.membership.dto.request.BillingCharge;
import com.msa.payment.domain.membership.dto.response.BillingReply;

/** 동일 시도는 동일 키로만 전송하고 불명 결과를 새 청구로 바꾸지 않는 빌링 경계입니다. */
public interface BillingClient {
    BillingReply issue(String authKey, String customerKey, String idempotencyKey);

    BillingReply charge(String billingKey, BillingCharge charge);

    BillingReply lookup(BillingCharge charge, String paymentKey);

    BillingReply delete(String billingKey);
}
