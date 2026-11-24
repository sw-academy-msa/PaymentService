package com.msa.payment.domain.membership.service.impl;

import com.msa.payment.domain.membership.client.BillingClient;
import com.msa.payment.domain.membership.dto.response.*;
import com.msa.payment.domain.membership.service.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** HTTP는 임대 커밋 후 실행하고 결과 불명 시 같은 주문과 키만 재사용합니다. */
@Service
@ConditionalOnProperty(name = "membership.billing.enabled", havingValue = "true")
public class MembershipProcessorImpl implements MembershipProcessor {
    private final MembershipWorkStore store;
    private final BillingClient client;

    public MembershipProcessorImpl(MembershipWorkStore store, BillingClient client) {
        this.store = store;
        this.client = client;
    }

    public void tick() {
        for (int n = 0; n < 10 && store.advance(); n++) {}
        MembershipWork work = store.claimIssue();
        if (work == null) work = store.claimCharge();
        if (work == null) work = store.claimDelete();
        if (work == null) return;
        BillingReply reply;
        switch (work.action()) {
            case "ISSUE" ->
                    reply =
                            client.issue(
                                    work.authKey(),
                                    work.customerKey(),
                                    "sdm-issue-" + work.workId());
            case "DELETE" -> reply = client.delete(work.billingKey());
            case "CHARGE" -> reply = client.charge(work.billingKey(), work.charge());
            case "LOOKUP" -> reply = client.lookup(work.charge(), work.paymentKey());
            default -> throw new IllegalArgumentException("멤버십 작업 불일치");
        }
        store.complete(work, reply);
    }
}
