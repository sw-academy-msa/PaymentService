package com.msa.payment.domain.outbox.service.impl;

import com.msa.payment.domain.outbox.repository.OutboxEventRepository;
import com.msa.payment.domain.outbox.service.OutboxPublisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/** Outbox 행만 잠그고 JPA 변경 감지로 결과를 기록합니다. 전송 중 업무 원본 행은 잠그지 않습니다. */
@Service
@ConditionalOnProperty(name = "payment.outbox.publisher-enabled", havingValue = "true")
public class OutboxPublisherImpl implements OutboxPublisher {
    private final OutboxEventRepository events;
    private final KafkaTemplate<String, byte[]> kafka;
    private final Clock clock;

    public OutboxPublisherImpl(
            OutboxEventRepository events, KafkaTemplate<String, byte[]> kafka, Clock clock) {
        this.events = events;
        this.kafka = kafka;
        this.clock = clock;
    }

    /** SKIP LOCKED의 잠금은 ack 후 결과 저장까지 같은 트랜잭션에 유지합니다. 중복 발행은 소비자가 멱등 처리합니다. */
    @Transactional(timeout = 20)
    public boolean publishNext() {
        var found = events.lockNextPending(clock.instant());
        if (found.isEmpty()) return false;
        var event = found.get();
        try {
            kafka.send(
                            event.topic,
                            event.partitionKey,
                            event.payload.getBytes(StandardCharsets.UTF_8))
                    .get(12, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            long delay = Math.min(60, 1L << Math.min(6, event.attempts));
            event.attempts++;
            event.nextAttemptAt = clock.instant().plusSeconds(delay);
            return true;
        }
        // DB 저장 실패를 전송 실패로 삼키지 않습니다. 롤백되면 다음 시도에 같은 이벤트가 다시 전송됩니다.
        event.publishedAt = clock.instant();
        event.attempts++;
        return true;
    }
}
