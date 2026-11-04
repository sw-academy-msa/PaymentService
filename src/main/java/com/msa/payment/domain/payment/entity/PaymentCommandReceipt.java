package com.msa.payment.domain.payment.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 명령 ID 재사용과 같은 버전의 다른 명령을 막습니다. 내용은 해시만 저장합니다. */
@Entity
@Table(
        name = "payment_command_receipts",
        schema = "payment",
        uniqueConstraints = @UniqueConstraint(columnNames = {"attemptId", "commandVersion"}))
public class PaymentCommandReceipt {
    /** 빌더에 담긴 개인정보·결제키·토큰·이벤트 본문을 로그에 노출하지 않습니다. */
    public static class PaymentCommandReceiptBuilder {
        @Override
        public String toString() {
            return "PaymentCommandReceiptBuilder[REDACTED]";
        }
    }

    @Id public UUID id;

    @Column(nullable = false)
    public UUID attemptId;

    @Column(nullable = false)
    public long commandVersion;

    @Column(nullable = false, length = 64)
    public String payloadHash;

    @Column(nullable = false)
    public Instant processedAt;

    protected PaymentCommandReceipt() {}

    // 기존 생성자를 거쳐 UUID·초기 상태·시간과 컬렉션 초기화를 보존하며 JPA 기본 생성자도 유지합니다.
    @Builder
    public PaymentCommandReceipt(UUID id, UUID attempt, long version, String hash, Instant now) {
        this.id = id;
        this.attemptId = attempt;
        this.commandVersion = version;
        this.payloadHash = hash;
        this.processedAt = now;
    }
}
