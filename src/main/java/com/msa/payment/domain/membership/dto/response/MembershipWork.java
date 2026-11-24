package com.msa.payment.domain.membership.dto.response;

import com.msa.payment.domain.membership.dto.request.BillingCharge;

import java.util.UUID;

/** 커밋된 임대의 내부 호출 자료이며 HTTP 동안 DB 트랜잭션을 유지하지 않습니다. */
public record MembershipWork(
        String action,
        UUID subscriptionId,
        long generation,
        UUID workId,
        UUID leaseToken,
        String customerKey,
        String authKey,
        String billingKey,
        BillingCharge charge,
        String paymentKey,
        boolean firstRequest) {
    @Override
    public String toString() {
        return "MembershipWork[REDACTED]";
    }
}
