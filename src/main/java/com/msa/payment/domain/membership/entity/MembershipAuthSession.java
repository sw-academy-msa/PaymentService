package com.msa.payment.domain.membership.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.*;
import java.util.UUID;

/** 본인 카드 인증과 발급 복구를 위한 암호화 세션입니다. */
@Entity
@Table(
        name = "membership_auth_sessions",
        schema = "payment",
        indexes = {
            @Index(
                    name = "idx_membership_auth_due",
                    columnList = "state,next_attempt_at,lease_until"),
            @Index(name = "idx_membership_auth_expiry", columnList = "expires_at")
        })
public class MembershipAuthSession {
    @Id public UUID id;

    @Column(nullable = false)
    public UUID accountId;

    @Column(nullable = false)
    public UUID subscriptionId;

    @Column(nullable = false)
    public long generation;

    @Column(nullable = false, columnDefinition = "text")
    public String customerKeyCipher;

    @Column(columnDefinition = "text")
    public String authKeyCipher;

    @Column(nullable = false, length = 40)
    public String encryptionKeyId;

    @Column(nullable = false, length = 40)
    public String providerKeyVersion;

    @Column(nullable = false)
    public String state;

    @Column(nullable = false)
    public Instant expiresAt;

    @Column(nullable = false, unique = true, length = 100)
    public String idempotencyKey;

    @Column(nullable = false, length = 64)
    public String payloadHash;

    @Column(length = 64)
    public String completionHash;

    public UUID completionKey;

    public UUID leaseToken;

    public Instant leaseUntil;

    @Column(nullable = false)
    public int attempts;

    @Column(nullable = false)
    public Instant nextAttemptAt;

    @Column(nullable = false, length = 40)
    public String termsVersion;

    @Column(nullable = false)
    public Instant agreedAt;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    protected MembershipAuthSession() {}

    @Builder
    public MembershipAuthSession(
            UUID id,
            UUID accountId,
            UUID subscriptionId,
            long generation,
            String customerKeyCipher,
            String authKeyCipher,
            String encryptionKeyId,
            String providerKeyVersion,
            String state,
            Instant expiresAt,
            String idempotencyKey,
            String payloadHash,
            String completionHash,
            UUID completionKey,
            UUID leaseToken,
            Instant leaseUntil,
            int attempts,
            Instant nextAttemptAt,
            String termsVersion,
            Instant agreedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.accountId = accountId;
        this.subscriptionId = subscriptionId;
        this.generation = generation;
        this.customerKeyCipher = customerKeyCipher;
        this.authKeyCipher = authKeyCipher;
        this.encryptionKeyId = encryptionKeyId;
        this.providerKeyVersion = providerKeyVersion;
        this.state = state;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
        this.completionHash = completionHash;
        this.completionKey = completionKey;
        this.leaseToken = leaseToken;
        this.leaseUntil = leaseUntil;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.termsVersion = termsVersion;
        this.agreedAt = agreedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 빌더의 결제 정보와 동의 원문을 로그에 노출하지 않습니다. */
    public static class MembershipAuthSessionBuilder {
        @Override
        public String toString() {
            return "MembershipAuthSessionBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "MembershipAuthSession[REDACTED]";
    }
}
