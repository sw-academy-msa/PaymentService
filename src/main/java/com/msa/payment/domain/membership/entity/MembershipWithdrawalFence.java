package com.msa.payment.domain.membership.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.util.UUID;

/** 탈퇴 준비 중 새 청구를 막고 해제 tombstone으로 지연 요청의 재차단을 방지합니다. */
@Entity
@Table(
        name = "membership_withdrawal_fences",
        schema = "payment",
        indexes =
                @Index(
                        name = "idx_membership_withdrawal_account",
                        columnList = "accountId,canceled"))
public class MembershipWithdrawalFence {
    @Id public UUID id;

    @Column(nullable = false, updatable = false)
    public UUID accountId;

    @Column(nullable = false)
    public boolean canceled;

    @Column(nullable = false)
    public boolean finalized;

    protected MembershipWithdrawalFence() {}

    @Builder
    public MembershipWithdrawalFence(UUID id, UUID accountId) {
        this.id = id;
        this.accountId = accountId;
    }

    @Override
    public String toString() {
        return "MembershipWithdrawalFence[REDACTED]";
    }

    /** 탈퇴 작업의 식별자를 문자열로 출력하지 않습니다. */
    public static class MembershipWithdrawalFenceBuilder {
        @Override
        public String toString() {
            return "MembershipWithdrawalFenceBuilder[REDACTED]";
        }
    }
}
