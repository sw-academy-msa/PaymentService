package com.msa.payment.domain.membership.service;

/** 해지 명령의 계약과 본문 해시를 검증하고 갱신 중지 의도를 영속화합니다. */
public interface MembershipCommandService {
    void receive(String key, byte[] payload);
}
