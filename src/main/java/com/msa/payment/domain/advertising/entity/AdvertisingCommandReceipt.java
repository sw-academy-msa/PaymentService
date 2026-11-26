package com.msa.payment.domain.advertising.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** 수신 이벤트의 본문 충돌을 검출하고 취소 선도착 시 영속 의도를 보관합니다. */
@Entity
@Table(
        name = "advertising_command_receipts",
        schema = "payment",
        uniqueConstraints =
                @UniqueConstraint(columnNames = {"application_id", "aggregate_version"}))
public class AdvertisingCommandReceipt {
    @Id public UUID eventId;

    @Column(nullable = false)
    public UUID applicationId;

    @Column(nullable = false)
    public UUID submissionId;

    @Column(nullable = false)
    public UUID productId;

    @Column(nullable = false)
    public UUID sellerId;

    @Column(nullable = false)
    public long aggregateVersion;

    @Column(nullable = false, length = 64)
    public String payloadHash;

    @Column(nullable = false, length = 50)
    public String eventType;

    @Column(nullable = false)
    public Instant processedAt;

    protected AdvertisingCommandReceipt() {}

    /** 원본 식별자를 보존해 취소 tombstone이 다른 신청을 막지 않게 합니다. */
    public AdvertisingCommandReceipt(
            UUID eventId,
            UUID applicationId,
            UUID submissionId,
            UUID productId,
            UUID sellerId,
            String hash,
            String eventType,
            long aggregateVersion,
            Instant now) {
        this.eventId = eventId;
        this.applicationId = applicationId;
        this.submissionId = submissionId;
        this.productId = productId;
        this.sellerId = sellerId;
        this.payloadHash = hash;
        this.eventType = eventType;
        this.aggregateVersion = aggregateVersion;
        this.processedAt = now;
    }

    @Override
    public String toString() {
        return "AdvertisingCommandReceipt[REDACTED]";
    }
}
