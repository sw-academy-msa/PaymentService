package com.msa.payment.domain.membership.service;

/** 한 번에 한 건만 외부 처리하여 인스턴스별 병렬 청구와 DB 연결 사용을 제한합니다. */
public interface MembershipProcessor {
    void tick();
}
