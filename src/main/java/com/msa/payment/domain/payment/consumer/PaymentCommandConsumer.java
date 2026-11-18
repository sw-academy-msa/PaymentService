package com.msa.payment.domain.payment.consumer;

import com.msa.payment.domain.payment.service.PaymentCommandService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 원본/이력 저장이 커밋된 뒤 offset을 진행합니다. 토스 네트워크 호출은 소비와 분리합니다. */
@Component
@ConditionalOnProperty(name = "payment.messaging.enabled", havingValue = "true")
public class PaymentCommandConsumer {
    private final PaymentCommandService commands;

    public PaymentCommandConsumer(PaymentCommandService commands) {
        this.commands = commands;
    }

    @KafkaListener(
            id = "payment-commands",
            idIsGroup = false,
            topics = "stockdrop.payment.commands.private.v1")
    public void consume(ConsumerRecord<String, byte[]> record) {
        commands.receive(record.key(), record.value());
    }
}
