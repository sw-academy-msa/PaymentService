package com.msa.payment.domain.membership.dto.response;

import java.time.Instant;

/** 공급자 응답에서 검증한 결과만 전달하며 결제키와 카드 원문은 문자열로 출력하지 않습니다. */
public record BillingReply(
        Status status,
        String billingKey,
        String paymentKey,
        Instant approvedAt,
        String failureCode) {
    public enum Status {
        ISSUED,
        APPROVED,
        DECLINED,
        UNKNOWN,
        DELETED
    }

    public static BillingReply unknown(String code) {
        return new BillingReply(Status.UNKNOWN, null, null, null, code);
    }

    @Override
    public String toString() {
        return "BillingReply[status=" + status + ",data=REDACTED]";
    }
}
