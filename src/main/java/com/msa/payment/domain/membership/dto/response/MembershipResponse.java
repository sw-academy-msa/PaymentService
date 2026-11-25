package com.msa.payment.domain.membership.dto.response;

import com.msa.payment.domain.membership.entity.MembershipSubscription;

import java.time.Instant;
import java.util.UUID;

/** 카드 식별자를 제외한 본인 구독의 금융 상태 스냅샷입니다. */
public record MembershipResponse(
        UUID subscriptionId,
        String state,
        boolean autoRenew,
        Instant periodStart,
        Instant periodEnd,
        Instant entitlementUntil,
        Instant nextChargeAt,
        long amount,
        String currency,
        long version,
        boolean billingRegistrationRequired) {
    public static MembershipResponse from(MembershipSubscription s) {
        if (s == null)
            return new MembershipResponse(
                    null, "NONE", false, null, null, null, null, 0, "KRW", 0, true);
        return new MembershipResponse(
                s.id,
                s.state,
                s.autoRenew,
                s.periodStart,
                s.periodEnd,
                s.entitlementUntil,
                s.nextChargeAt,
                s.priceAmount,
                s.currency,
                s.aggregateVersion,
                "EXPIRED".equals(s.state) || "NONE".equals(s.state));
    }
}
