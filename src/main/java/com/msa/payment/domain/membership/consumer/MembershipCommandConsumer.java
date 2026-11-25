package com.msa.payment.domain.membership.consumer;

import com.msa.payment.domain.membership.service.MembershipCommandService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 멤버십 전용 유한 재시도와 DLT를 사용하며 업무 커밋 후 offset을 진행합니다. */
@Component
@ConditionalOnProperty(name = "membership.consumer-enabled", havingValue = "true")
public class MembershipCommandConsumer {
    private final MembershipCommandService service;

    public MembershipCommandConsumer(MembershipCommandService service) {
        this.service = service;
    }

    @KafkaListener(
            id = "membership-commands",
            groupId = "payment-membership-command-v1",
            topics = "stockdrop.membership.commands.private.v1",
            containerFactory = "membershipKafkaListenerContainerFactory",
            autoStartup = "${membership.consumer-enabled:false}")
    public void consume(ConsumerRecord<String, byte[]> record) {
        service.receive(record.key(), record.value());
    }
}
