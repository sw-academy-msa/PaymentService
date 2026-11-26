package com.msa.payment.domain.advertising.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** 광고 회차별 금융 원본을 보관하며 구매 주문 및 정기 청구와 분리합니다. */
@Entity
@Table(name = "advertising_payments", schema = "payment")
public class AdvertisingPayment {
    @Id public UUID id;

    @Column(nullable = false, unique = true)
    public UUID applicationId;

    @Column(nullable = false)
    public UUID submissionId;

    @Column(nullable = false)
    public UUID productId;

    @Column(nullable = false)
    public UUID sellerId;

    @Column(nullable = false)
    public long amount;

    @Column(nullable = false, length = 3)
    public String currency;

    @Column(nullable = false, length = 20)
    public String formulaVersion;

    @Column(nullable = false, length = 64)
    public String quoteHash;

    @Column(nullable = false, unique = true, length = 50)
    public String tossOrderId;

    @Column(unique = true, length = 200)
    public String paymentKey;

    @Column(nullable = false, length = 20)
    public String status;

    @Column(nullable = false, length = 20)
    public String providerState;

    public boolean cancellationRequested;

    @Column(length = 40)
    public String cancellationReasonCode;

    public Instant approvedAt;
    public Instant canceledAt;

    @Column(nullable = false)
    public Instant expiresAt;

    public long resultVersion;
    public UUID completionKey;
    public UUID leaseToken;
    public Instant leaseUntil;
    public int attempts;

    @Column(nullable = false)
    public Instant nextAttemptAt;

    @Column(length = 64)
    public String lastFailureCode;

    public boolean confirmationStarted;
    public boolean approvalRequested;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    protected AdvertisingPayment() {}

    /** 신뢰한 Product 명령의 스냅샷만으로 공급자 결제 원본을 만듭니다. */
    public static AdvertisingPayment prepare(
            UUID applicationId,
            UUID submissionId,
            UUID productId,
            UUID sellerId,
            long amount,
            String quoteHash,
            Instant expiresAt,
            Instant now) {
        var p = new AdvertisingPayment();
        p.id = UUID.randomUUID();
        p.applicationId = applicationId;
        p.submissionId = submissionId;
        p.productId = productId;
        p.sellerId = sellerId;
        p.amount = amount;
        p.currency = "KRW";
        p.formulaVersion = "V1";
        p.quoteHash = quoteHash;
        p.tossOrderId = "AD-" + p.id;
        p.status = "PENDING";
        p.providerState = "PENDING";
        p.expiresAt = expiresAt;
        p.nextAttemptAt = now;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    @Override
    public String toString() {
        return "AdvertisingPayment[REDACTED]";
    }
}
