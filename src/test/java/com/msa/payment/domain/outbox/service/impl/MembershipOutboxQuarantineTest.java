package com.msa.payment.domain.outbox.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.payment.domain.outbox.entity.OutboxEvent;
import com.msa.payment.domain.outbox.repository.OutboxEventRepository;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** 멤버십만 유한 실패 후 격리하고 기존 주문 결제의 재시도 계약은 유지합니다. */
class MembershipOutboxQuarantineTest {
    @Test
    void twentiethMembershipFailureIsQuarantinedWithOriginalEventPreserved() {
        var repo = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        var now = Instant.parse("2030-01-01T00:00:00Z");
        var publisher = new OutboxPublisherImpl(repo, kafka, Clock.fixed(now, ZoneOffset.UTC));
        when(kafka.send(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("fixture")));
        for (String topic :
                List.of(
                        "stockdrop.membership.events.private.v1",
                        "stockdrop.payment.results.private.v1")) {
            var event =
                    OutboxEvent.builder()
                            .id(UUID.randomUUID())
                            .topic(topic)
                            .aggregateId(UUID.randomUUID())
                            .payload("{\"fixture\":true}")
                            .now(now)
                            .build();
            event.attempts = 19;
            when(repo.lockNextPending(now)).thenReturn(Optional.of(event));
            assertThat(publisher.publishNext()).isTrue();
            assertThat(event.attempts).isEqualTo(20);
            assertThat(event.publishedAt).isNull();
            assertThat(event.payload).isEqualTo("{\"fixture\":true}");
            if (topic.contains("membership")) assertThat(event.failedAt).isEqualTo(now);
            else assertThat(event.failedAt).isNull();
        }
    }
}
