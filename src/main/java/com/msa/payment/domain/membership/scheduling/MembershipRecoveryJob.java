package com.msa.payment.domain.membership.scheduling;

import com.msa.payment.domain.membership.service.MembershipProcessor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 전용 단일 실행기로 공급자 지연이 주문 결제와 Outbox를 막지 않게 합니다. */
@Component
@ConditionalOnProperty(name = "membership.billing.enabled", havingValue = "true")
public class MembershipRecoveryJob {
    private final MembershipProcessor processor;

    public MembershipRecoveryJob(MembershipProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${membership.poll-delay:30000}",
            initialDelayString = "${membership.poll-delay:30000}",
            scheduler = "membershipWorkerScheduler")
    public void run() {
        processor.tick();
    }
}
