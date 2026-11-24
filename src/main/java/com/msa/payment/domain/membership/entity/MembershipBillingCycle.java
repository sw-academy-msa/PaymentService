package com.msa.payment.domain.membership.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.*;
import java.util.UUID;

/** 한 납부기간에 대한 고정 금액과 업무 재시도를 보관합니다. */
@Entity
@Table(
        name = "membership_billing_cycles",
        schema = "payment",
        uniqueConstraints = {
            @UniqueConstraint(columnNames = {"subscription_id", "generation", "cycle_number"})
        },
        indexes = {
            @Index(name = "idx_membership_cycle_due", columnList = "status,next_attempt_at")
        })
public class MembershipBillingCycle {
    @Id public UUID id;

    @Column(nullable = false)
    public UUID subscriptionId;

    @Column(nullable = false)
    public long generation;

    @Column(nullable = false)
    public long cycleNumber;

    @Column(nullable = false)
    public Instant scheduledAt;

    public Instant periodStart;

    public Instant periodEnd;

    @Column(nullable = false)
    public long amount;

    @Column(nullable = false, length = 3)
    public String currency;

    @Column(nullable = false)
    public String status;

    @Column(nullable = false)
    public int retryNumber;

    public Instant nextAttemptAt;

    public Instant graceUntil;

    public UUID succeededAttemptId;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    protected MembershipBillingCycle() {}

    @Builder
    public MembershipBillingCycle(
            UUID id,
            UUID subscriptionId,
            long generation,
            long cycleNumber,
            Instant scheduledAt,
            Instant periodStart,
            Instant periodEnd,
            long amount,
            String currency,
            String status,
            int retryNumber,
            Instant nextAttemptAt,
            Instant graceUntil,
            UUID succeededAttemptId,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.generation = generation;
        this.cycleNumber = cycleNumber;
        this.scheduledAt = scheduledAt;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.retryNumber = retryNumber;
        this.nextAttemptAt = nextAttemptAt;
        this.graceUntil = graceUntil;
        this.succeededAttemptId = succeededAttemptId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 빌더의 결제 정보와 동의 원문을 로그에 노출하지 않습니다. */
    public static class MembershipBillingCycleBuilder {
        @Override
        public String toString() {
            return "MembershipBillingCycleBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "MembershipBillingCycle[REDACTED]";
    }
}
