package com.msa.payment.domain.outbox.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 업무 트랜잭션이 보관하는 불변 이벤트입니다. 발행 완료는 소비 완료를 뜻하지 않습니다. */
@Entity
@Table(
        name = "outbox_events",
        schema = "payment",
        indexes =
                @Index(
                        name = "idx_outbox_pending",
                        columnList = "publishedAt,nextAttemptAt,createdAt"))
public class OutboxEvent {
    /** 빌더에 담긴 개인정보·결제키·토큰·이벤트 본문을 로그에 노출하지 않습니다. */
    public static class OutboxEventBuilder {
        @Override
        public String toString() {
            return "OutboxEventBuilder[REDACTED]";
        }
    }

    @Id public UUID id;

    @Column(nullable = false)
    public String topic;

    @Column(nullable = false)
    public String partitionKey;

    @Column(nullable = false, columnDefinition = "text")
    public String payload;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant nextAttemptAt;

    public Instant publishedAt;
    public int attempts;

    protected OutboxEvent() {}

    // 기존 생성자를 거쳐 UUID·초기 상태·시간과 컬렉션 초기화를 보존하며 JPA 기본 생성자도 유지합니다.
    @Builder
    public OutboxEvent(UUID id, String topic, UUID aggregateId, String payload, Instant now) {
        this.id = id;
        this.topic = topic;
        this.partitionKey = aggregateId.toString();
        this.payload = payload;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }
}
