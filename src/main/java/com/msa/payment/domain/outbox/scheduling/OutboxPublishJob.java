package com.msa.payment.domain.outbox.scheduling;

import com.msa.payment.domain.outbox.service.OutboxPublisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Kafka 전송은 결제 결과 저장 이후 별도 내부 작업입니다. 발행 응답 원문을 로그에 남기지 않습니다. */
@Component
@ConditionalOnProperty(name = "payment.outbox.publisher-enabled", havingValue = "true")
public class OutboxPublishJob {
    private final OutboxPublisher publisher;

    public OutboxPublishJob(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(
            fixedDelayString = "${payment.outbox.poll-ms:1000}",
            scheduler = "paymentOutboxScheduler")
    public void publish() {
        for (int i = 0; i < 20 && publisher.publishNext(); i++) {
            /* 실행당 처리량을 제한합니다. */
        }
    }
}
