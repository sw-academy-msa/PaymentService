package com.msa.payment.domain.analytics.scheduling;

import com.msa.payment.domain.analytics.service.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 전용 실행기에서 한 번에 열 건만 처리하여 결제 워커를 점유하지 않습니다. */
@Component
@ConditionalOnProperty(name = "payment.ga.enabled", havingValue = "true")
public class GaPurchaseJob {
    private final GaProcessor processor;
    private final GaDispatchStore store;

    public GaPurchaseJob(GaProcessor processor, GaDispatchStore store) {
        this.processor = processor;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${payment.ga.poll-ms:30000}", scheduler = "gaWorkerScheduler")
    public void run() {
        for (int i = 0; i < 10 && processor.processNext(); i++) {
            /* 유한 묶음입니다. */
        }
    }

    @Scheduled(fixedDelayString = "PT24H", scheduler = "gaWorkerScheduler")
    public void purge() {
        store.purge();
    }
}
