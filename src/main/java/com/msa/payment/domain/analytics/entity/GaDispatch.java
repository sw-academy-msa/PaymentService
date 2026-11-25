package com.msa.payment.domain.analytics.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** 주문과 종류의 유일 키로 분석 전송 중복과 복제본 경합을 막습니다. */
@Entity
@Table(name = "ga_purchase_dispatch", schema = "payment")
@IdClass(GaDispatch.Key.class)
public class GaDispatch {
    @Id public UUID orderId;
    @Id public String kind;

    @Column(columnDefinition = "text")
    public String payload;

    public enum Status {
        PENDING,
        SENT,
        SKIPPED,
        FAILED
    }

    @Enumerated(EnumType.STRING)
    public Status status;

    public int attempts;
    public Instant nextAttemptAt;
    public UUID leaseToken;
    public Instant leaseUntil;
    public Instant createdAt;
    public Instant finishedAt;
    public long amount;

    protected GaDispatch() {}

    @Builder
    public GaDispatch(UUID orderId, String kind, String payload, long amount, Instant now) {
        this.orderId = orderId;
        this.kind = kind;
        this.payload = payload;
        this.amount = amount;
        this.status = Status.PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    /** 복합 키도 로그에서 가명 데이터를 노출하지 않습니다. */
    public record Key(UUID orderId, String kind) implements Serializable {}

    /** 빌더의 분석 본문을 숨깁니다. */
    public static class GaDispatchBuilder {
        @Override
        public String toString() {
            return "GaDispatchBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "GaDispatch[REDACTED]";
    }
}
