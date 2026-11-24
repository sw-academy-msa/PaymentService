package com.msa.payment.domain.membership.service;

import com.msa.payment.domain.membership.entity.MembershipSubscription;

/** 금융 원본 변경과 같은 트랜잭션에 완전 자격 스냅샷을 기록합니다. */
public interface MembershipResultOutboxService {
    void record(MembershipSubscription subscription, String type, long cycleNumber);
}
