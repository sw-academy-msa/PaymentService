package com.msa.payment.domain.membership.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.*;
import java.util.UUID;

/** 해지 명령의 중복과 동일 버전의 다른 본문을 구분합니다. */
@Entity
@Table(
        name = "membership_command_receipts",
        schema = "payment",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"account_id", "command_version"})})
public class MembershipCommandReceipt {
    @Id public UUID eventId;

    @Column(nullable = false)
    public UUID accountId;

    @Column(nullable = false)
    public long commandVersion;

    @Column(nullable = false, length = 64)
    public String payloadHash;

    @Column(nullable = false)
    public Instant processedAt;

    protected MembershipCommandReceipt() {}

    @Builder
    public MembershipCommandReceipt(
            UUID eventId,
            UUID accountId,
            long commandVersion,
            String payloadHash,
            Instant processedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.commandVersion = commandVersion;
        this.payloadHash = payloadHash;
        this.processedAt = processedAt;
    }

    /** 빌더의 결제 정보와 동의 원문을 로그에 노출하지 않습니다. */
    public static class MembershipCommandReceiptBuilder {
        @Override
        public String toString() {
            return "MembershipCommandReceiptBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "MembershipCommandReceipt[REDACTED]";
    }
}
