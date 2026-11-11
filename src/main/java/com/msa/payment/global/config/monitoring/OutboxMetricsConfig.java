package com.msa.payment.global.config.monitoring;

import com.msa.payment.domain.outbox.repository.OutboxEventRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 수집 HTTP 요청이 DB를 잠그거나 지연시키지 않도록 집계 캐시를 주기적으로 갱신합니다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "monitoring.outbox-enabled", havingValue = "true")
public class OutboxMetricsConfig {
    private final OutboxEventRepository repository;
    private final AtomicLong pending = new AtomicLong(-1);
    private final AtomicLong available = new AtomicLong(0);
    private final AtomicReference<Instant> oldest = new AtomicReference<>();

    public OutboxMetricsConfig(OutboxEventRepository repository, MeterRegistry registry) {
        this.repository = repository;
        Gauge.builder("stockdrop.outbox.pending", pending, AtomicLong::get).register(registry);
        Gauge.builder("stockdrop.outbox.collector.available", available, AtomicLong::get)
                .register(registry);
        Gauge.builder("stockdrop.outbox.oldest.pending.seconds", this, c -> c.age())
                .register(registry);
    }

    /** 업무 변경과 분리된 읽기 집계이며 실패를 정상 0건으로 덮어쓰지 않습니다. */
    @Scheduled(
            fixedDelayString = "${monitoring.outbox-poll-ms:30000}",
            initialDelayString = "${monitoring.outbox-poll-ms:30000}")
    public void sample() {
        try {
            var snapshot = repository.monitoringSnapshot();
            pending.set(snapshot.getPending());
            oldest.set(snapshot.getOldest());
            available.set(1);
        } catch (RuntimeException failure) {
            available.set(0);
            org.slf4j.LoggerFactory.getLogger(OutboxMetricsConfig.class)
                    .warn(
                            "outbox metrics unavailable cause={}",
                            failure.getClass().getSimpleName());
        }
    }

    private double age() {
        if (pending.get() < 0) return Double.NaN;
        Instant time = oldest.get();
        return time == null ? 0 : Math.max(0, Duration.between(time, Instant.now()).getSeconds());
    }
}
