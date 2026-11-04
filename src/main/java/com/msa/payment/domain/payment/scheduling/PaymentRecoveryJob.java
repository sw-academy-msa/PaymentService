package com.msa.payment.domain.payment.scheduling;

import com.msa.payment.domain.payment.service.PaymentProcessor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 서버 재시작·타임아웃으로 남은 결제 시도를 재개합니다. 브라우저가 열려 있을 필요가 없습니다. */
@Component
@ConditionalOnProperty(name = "payment.processing.enabled", havingValue = "true")
public class PaymentRecoveryJob {
    private final PaymentProcessor processor;

    public PaymentRecoveryJob(PaymentProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${payment.processing.poll-ms:1000}",
            scheduler = "paymentWorkerScheduler")
    public void recover() {
        for (int i = 0; i < 20 && processor.processNext(); i++) {
            /* 한 실행의 무한 점유를 막습니다. */
        }
    }
}
