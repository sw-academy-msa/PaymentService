package com.msa.payment.domain.outbox.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** 빌더가 이벤트의 파티션 키와 최초 발행 대기 상태를 유지하는지 검증합니다. */
class OutboxEventTest {
    @Test
    void builderKeepsPartitionKeyAndPendingPublication() {
        var aggregate = UUID.randomUUID();
        var now = Instant.parse("2030-01-01T00:00:00Z");
        var event =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .topic("fixture-results")
                        .aggregateId(aggregate)
                        .payload("{}")
                        .now(now)
                        .build();

        assertThat(event.partitionKey).isEqualTo(aggregate.toString());
        assertThat(event.createdAt).isEqualTo(now);
        assertThat(event.nextAttemptAt).isEqualTo(now);
        assertThat(event.publishedAt).isNull();
        assertThat(event.attempts).isZero();
    }
}
