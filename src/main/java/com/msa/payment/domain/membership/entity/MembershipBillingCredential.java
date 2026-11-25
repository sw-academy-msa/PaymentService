package com.msa.payment.domain.membership.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.*;
import java.util.UUID;

/** 공급자 삭제가 확인될 때까지 암호화한 빌링 정보를 보존합니다. */
@Entity
@Table(
        name = "membership_billing_credentials",
        schema = "payment",
        indexes = {
            @Index(name = "idx_membership_delete", columnList = "delete_state,delete_next_at")
        })
public class MembershipBillingCredential {
    @Id public UUID subscriptionId;

    @Column(columnDefinition = "text")
    public String customerKeyCipher;

    @Column(columnDefinition = "text")
    public String billingKeyCipher;

    @Column(nullable = false, length = 40)
    public String encryptionKeyId;

    @Column(nullable = false, length = 24)
    public String deleteState;

    public Instant deleteNextAt;

    public UUID leaseToken;

    public Instant leaseUntil;

    @Column(nullable = false)
    public int attempts;

    @Column(length = 64)
    public String lastFailureCode;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    protected MembershipBillingCredential() {}

    @Builder
    public MembershipBillingCredential(
            UUID subscriptionId,
            String customerKeyCipher,
            String billingKeyCipher,
            String encryptionKeyId,
            String deleteState,
            Instant deleteNextAt,
            UUID leaseToken,
            Instant leaseUntil,
            int attempts,
            String lastFailureCode,
            Instant createdAt,
            Instant updatedAt) {
        this.subscriptionId = subscriptionId;
        this.customerKeyCipher = customerKeyCipher;
        this.billingKeyCipher = billingKeyCipher;
        this.encryptionKeyId = encryptionKeyId;
        this.deleteState = deleteState;
        this.deleteNextAt = deleteNextAt;
        this.leaseToken = leaseToken;
        this.leaseUntil = leaseUntil;
        this.attempts = attempts;
        this.lastFailureCode = lastFailureCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 빌더의 결제 정보와 동의 원문을 로그에 노출하지 않습니다. */
    public static class MembershipBillingCredentialBuilder {
        @Override
        public String toString() {
            return "MembershipBillingCredentialBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "MembershipBillingCredential[REDACTED]";
    }
}
