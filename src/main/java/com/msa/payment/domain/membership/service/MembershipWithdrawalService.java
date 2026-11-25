package com.msa.payment.domain.membership.service;

import java.util.UUID;

/** 탈퇴 준비와 확정을 분리하고 이미 시작한 금융 결과가 불명인 동안 성공을 거절합니다. */
public interface MembershipWithdrawalService {
    long inspect(UUID account);

    long prepare(UUID account, UUID operation);

    long finalizeWithdrawal(UUID account, UUID operation);

    void release(UUID account, UUID operation);
}
