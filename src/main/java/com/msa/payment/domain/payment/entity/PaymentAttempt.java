package com.msa.payment.domain.payment.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 결제 시도 원본입니다. 주문은 ID로만 참조하고 Order DB와 FK·공유 트랜잭션을 만들지 않습니다. */
@Entity
@Table(
        name = "payment_attempts",
        schema = "payment",
        indexes = {
            @Index(name = "idx_payment_work", columnList = "nextAttemptAt,leaseUntil"),
            // 복구 작업이 처리 대상 상태만 찾습니다. 종료된 결제도 재시도 시각이 남아 있어 시각만으로는 걸러지지 않습니다.
            @Index(
                    name = "idx_payment_due",
                    columnList = "status,cancellationRequested,nextAttemptAt")
        })
public class PaymentAttempt {
    /** 빌더에 담긴 개인정보·결제키·토큰·이벤트 본문을 로그에 노출하지 않습니다. */
    public static class PaymentAttemptBuilder {
        @Override
        public String toString() {
            return "PaymentAttemptBuilder[REDACTED]";
        }
    }

    public enum Status {
        PENDING,
        UNKNOWN,
        APPROVED,
        DECLINED,
        CANCELED
    }

    @Id public UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    public UUID orderId;

    @Column(nullable = false, updatable = false)
    public long amount;

    @Column(nullable = false, updatable = false)
    public Instant expiresAt;

    @Column(nullable = false, updatable = false)
    public Instant createdAt;

    // 승인 전에는 null일 수 있습니다. 원문은 API 응답·로그·결과 이벤트로 내보내지 않습니다.
    @Column(length = 200, unique = true)
    public String paymentKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.PENDING;

    @Column(nullable = false)
    public boolean approvalRequested;

    @Column(nullable = false)
    public boolean cancellationRequested;

    // 승인 HTTP를 보내기 전에 먼저 영속화합니다. 불명 시 결제 결과 확인 없이 재고 반환 근거로 쓰지 않습니다.
    @Column(nullable = false)
    public boolean confirmationStarted;

    @Column(nullable = false)
    public long resultVersion;

    public Instant approvedAt;
    public UUID leaseToken;
    public Instant leaseUntil;

    @Column(nullable = false)
    public Instant nextAttemptAt;

    @Column(nullable = false)
    public int attempts;

    @Column(length = 64)
    public String lastFailureCode;

    protected PaymentAttempt() {}

    // 기존 생성자를 거쳐 UUID·초기 상태·시간과 컬렉션 초기화를 보존하며 JPA 기본 생성자도 유지합니다.
    @Builder
    public PaymentAttempt(UUID id, UUID order, long amount, Instant expiresAt, Instant now) {
        this.id = id;
        this.orderId = order;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }
}
