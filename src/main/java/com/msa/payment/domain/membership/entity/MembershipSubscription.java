package com.msa.payment.domain.membership.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.*;
import java.util.UUID;

/** 회원별 구독과 동의 및 자격의 금융 원본을 보관합니다. */
@Entity
@Table(
        name = "membership_subscriptions",
        schema = "payment",
        indexes = {
            @Index(name = "idx_membership_due", columnList = "state,next_charge_at"),
            @Index(name = "idx_membership_expiry", columnList = "state,entitlement_until")
        })
public class MembershipSubscription {
    @Id public UUID id;

    @Column(nullable = false, unique = true)
    public UUID accountId;

    @Column(nullable = false)
    public long generation;

    @Column(nullable = false)
    public String state;

    @Column(nullable = false)
    public boolean autoRenew;

    @Column(nullable = false, length = 40)
    public String planVersion;

    @Column(nullable = false)
    public long priceAmount;

    @Column(nullable = false, length = 3)
    public String currency;

    @Column(nullable = false)
    public int anchorDay;

    public LocalTime anchorLocalTime;

    @Column(nullable = false, length = 40)
    public String zone;

    public Instant periodStart;

    public Instant periodEnd;

    public Instant entitlementUntil;

    public Instant graceUntil;

    public Instant nextChargeAt;

    public Instant canceledAt;

    @Column(nullable = false)
    public long aggregateVersion;

    @Version
    @Column(nullable = false)
    public long rowVersion;

    @Column(nullable = false, length = 40)
    public String termsVersion;

    @Column(nullable = false)
    public Instant agreedAt;

    @Column(nullable = false)
    public UUID agreedAccountId;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    protected MembershipSubscription() {}

    @Builder
    public MembershipSubscription(
            UUID id,
            UUID accountId,
            long generation,
            String state,
            boolean autoRenew,
            String planVersion,
            long priceAmount,
            String currency,
            int anchorDay,
            LocalTime anchorLocalTime,
            String zone,
            Instant periodStart,
            Instant periodEnd,
            Instant entitlementUntil,
            Instant graceUntil,
            Instant nextChargeAt,
            Instant canceledAt,
            long aggregateVersion,
            long rowVersion,
            String termsVersion,
            Instant agreedAt,
            UUID agreedAccountId,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.accountId = accountId;
        this.generation = generation;
        this.state = state;
        this.autoRenew = autoRenew;
        this.planVersion = planVersion;
        this.priceAmount = priceAmount;
        this.currency = currency;
        this.anchorDay = anchorDay;
        this.anchorLocalTime = anchorLocalTime;
        this.zone = zone;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.entitlementUntil = entitlementUntil;
        this.graceUntil = graceUntil;
        this.nextChargeAt = nextChargeAt;
        this.canceledAt = canceledAt;
        this.aggregateVersion = aggregateVersion;
        this.rowVersion = rowVersion;
        this.termsVersion = termsVersion;
        this.agreedAt = agreedAt;
        this.agreedAccountId = agreedAccountId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 빌더의 결제 정보와 동의 원문을 로그에 노출하지 않습니다. */
    public static class MembershipSubscriptionBuilder {
        @Override
        public String toString() {
            return "MembershipSubscriptionBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "MembershipSubscription[REDACTED]";
    }
}
