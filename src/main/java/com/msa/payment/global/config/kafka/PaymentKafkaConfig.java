package com.msa.payment.global.config.kafka;

import com.msa.payment.domain.payment.InvalidPaymentCommandException;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/** 계약 오류만 DLT로 격리합니다. DB 장애와 DLT 발행 장애는 offset을 넘기지 않고 재시도합니다. */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "payment.messaging.enabled", havingValue = "true")
public class PaymentKafkaConfig {
    @Bean
    public DefaultErrorHandler paymentCommandErrorHandler(KafkaTemplate<String, byte[]> kafka) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        kafka,
                        (record, ex) ->
                                new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        var handler =
                new DefaultErrorHandler(
                        recoverer, new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidPaymentCommandException.class);
        return handler;
    }
}
