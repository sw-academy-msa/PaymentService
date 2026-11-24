package com.msa.payment.domain.membership.service;

import com.msa.payment.domain.membership.dto.response.*;

/** 공급자 호출 전후를 짧은 독립 트랜잭션으로 나누고 동일 청구 임대를 복구합니다. */
public interface MembershipWorkStore {
    MembershipWork claimIssue();

    MembershipWork claimCharge();

    MembershipWork claimDelete();

    void complete(MembershipWork work, BillingReply reply);

    boolean advance();
}
