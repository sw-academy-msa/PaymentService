package com.msa.payment.domain.advertising.scheduling;

import com.msa.payment.domain.advertising.service.AdvertisingProcessor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 접수 플래그와 독립적으로 이미 시작한 광고 결제와 취소를 복구합니다. */
@Component
@ConditionalOnProperty(name = "payment.advertising.processing-enabled", havingValue = "true")
public class AdvertisingRecoveryJob {
    private final AdvertisingProcessor processor;

    public AdvertisingRecoveryJob(AdvertisingProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${payment.advertising.recovery-delay:1000}",
            initialDelayString = "${payment.advertising.recovery-delay:1000}")
    public void recover() {
        for (int i = 0; i < 10 && processor.processNext(); i++) {}
    }
}
