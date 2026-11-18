package com.msa.payment.global.config.scheduling;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 토스 지연이 Outbox 발행을 막지 않도록 실행기를 분리합니다. DB 임대가 복제본 중복 실행을 제어합니다. */
@Configuration
@EnableScheduling
public class PaymentSchedulingConfig {
    @Bean
    public ThreadPoolTaskScheduler paymentWorkerScheduler() {
        return scheduler("payment-worker-");
    }

    @Bean
    public ThreadPoolTaskScheduler paymentOutboxScheduler() {
        return scheduler("payment-outbox-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
