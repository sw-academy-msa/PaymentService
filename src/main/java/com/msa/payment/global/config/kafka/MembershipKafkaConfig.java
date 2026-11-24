package com.msa.payment.global.config.kafka;

import com.msa.payment.domain.membership.exception.InvalidMembershipCommandException;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/** 새 토픽만 세 번 재시도하며 DLT 송신 실패는 컨테이너를 정지해 무한 루프를 막습니다. */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "membership.consumer-enabled", havingValue = "true")
public class MembershipKafkaConfig {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]>
            membershipKafkaListenerContainerFactory(
                    ConsumerFactory<String, byte[]> consumerFactory,
                    KafkaTemplate<String, byte[]> kafka,
                    ObjectProvider<KafkaListenerEndpointRegistry> registry) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        kafka,
                        (record, failure) ->
                                new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        var handler =
                new DefaultErrorHandler(
                        (record, failure) -> {
                            try {
                                recoverer.accept(record, failure);
                            } catch (RuntimeException sendFailure) {
                                // DLT ack가 없으면 offset을 넘기지 않고 운영 복구 때 같은 이벤트를 받습니다.
                                var container =
                                        registry.getObject()
                                                .getListenerContainer("membership-commands");
                                if (container != null) container.stop(() -> {});
                                throw sendFailure;
                            }
                        },
                        new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(InvalidMembershipCommandException.class);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        var consumerSettings =
                new java.util.HashMap<String, Object>(consumerFactory.getConfigurationProperties());
        consumerSettings.put(
                org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        consumerSettings.put(
                org.apache.kafka.clients.consumer.ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,
                16384);
        factory.setConsumerFactory(
                new DefaultKafkaConsumerFactory<>(
                        consumerSettings,
                        new org.apache.kafka.common.serialization.StringDeserializer(),
                        new org.apache.kafka.common.serialization.ByteArrayDeserializer()));
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(handler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
