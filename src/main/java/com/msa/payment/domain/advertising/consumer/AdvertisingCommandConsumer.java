package com.msa.payment.domain.advertising.consumer;

import com.msa.payment.domain.advertising.service.AdvertisingCommandService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 구매 주문 토픽과 분리하고 커밋한 명령만 offset을 진행합니다. */
@Component
@ConditionalOnProperty(name = "payment.advertising.consumer-enabled", havingValue = "true")
public class AdvertisingCommandConsumer {
    private final AdvertisingCommandService service;

    public AdvertisingCommandConsumer(AdvertisingCommandService service) {
        this.service = service;
    }

    @KafkaListener(
            id = "advertising-commands",
            groupId = "payment-advertising-command-v1",
            topics = "product.advertising.commands.v1",
            containerFactory = "advertisingKafkaListenerContainerFactory",
            autoStartup =
                    "#{${payment.advertising.consumer-enabled:false} and"
                            + " ${spring.kafka.listener.auto-startup:true}}")
    public void consume(ConsumerRecord<String, byte[]> record) {
        service.receive(record.key(), record.value());
    }
}
