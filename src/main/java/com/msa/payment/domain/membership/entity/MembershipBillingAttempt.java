package com.msa.payment.domain.membership.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.*;
import java.util.UUID;

/** 전송 전에 고정한 주문과 멱등 키로 불명 결제를 복구합니다. */
@Entity
@Table(
        name = "membership_billing_attempts",
        schema = "payment",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"cycle_id", "attempt_number"})},
        indexes = {
            @Index(
                    name = "idx_membership_attempt_due",
                    columnList = "status,next_attempt_at,lease_until")
        })
public class MembershipBillingAttempt {
    @Id public UUID id;

    @Column(nullable = false)
    public UUID cycleId;

    @Column(nullable = false)
    public int attemptNumber;

    @Column(nullable = false, unique = true, length = 64)
    public String orderId;

    @Column(nullable = false, unique = true, length = 100)
    public String idempotencyKey;

    @Column(nullable = false)
    public String status;

    @Column(nullable = false)
    public long amount;

    @Column(nullable = false, length = 3)
    public String currency;

    @Column(nullable = false, length = 64)
    public String requestHash;

    @Column(unique = true, length = 200)
    public String paymentKey;

    @Column(nullable = false, length = 40)
    public String providerKeyVersion;

    public Instant firstRequestedAt;

    @Column(nullable = false)
    public Instant nextAttemptAt;

    public UUID leaseToken;

    public Instant leaseUntil;

    @Column(nullable = false)
    public int transportAttempts;

    @Column(length = 64)
    public String lastFailureCode;

    public Instant approvedAt;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    protected MembershipBillingAttempt() {}

    @Builder
    public MembershipBillingAttempt(
            UUID id,
            UUID cycleId,
            int attemptNumber,
            String orderId,
            String idempotencyKey,
            String status,
            long amount,
            String currency,
            String requestHash,
            String paymentKey,
            String providerKeyVersion,
            Instant firstRequestedAt,
            Instant nextAttemptAt,
            UUID leaseToken,
            Instant leaseUntil,
            int transportAttempts,
            String lastFailureCode,
            Instant approvedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.cycleId = cycleId;
        this.attemptNumber = attemptNumber;
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.amount = amount;
        this.currency = currency;
        this.requestHash = requestHash;
        this.paymentKey = paymentKey;
        this.providerKeyVersion = providerKeyVersion;
        this.firstRequestedAt = firstRequestedAt;
        this.nextAttemptAt = nextAttemptAt;
        this.leaseToken = leaseToken;
        this.leaseUntil = leaseUntil;
        this.transportAttempts = transportAttempts;
        this.lastFailureCode = lastFailureCode;
        this.approvedAt = approvedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 빌더의 결제 정보와 동의 원문을 로그에 노출하지 않습니다. */
    public static class MembershipBillingAttemptBuilder {
        @Override
        public String toString() {
            return "MembershipBillingAttemptBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "MembershipBillingAttempt[REDACTED]";
    }
}
