package com.msa.payment.domain.membership.repository;

import com.msa.payment.domain.membership.entity.MembershipCommandReceipt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 소비한 명령 ID와 계정별 명령 버전을 함께 중복 검사합니다. */
public interface MembershipCommandReceiptRepository
        extends JpaRepository<MembershipCommandReceipt, UUID> {
    boolean existsByAccountIdAndCommandVersion(UUID accountId, long commandVersion);

    boolean existsByAccountIdAndCommandVersionGreaterThan(UUID accountId, long commandVersion);
}
