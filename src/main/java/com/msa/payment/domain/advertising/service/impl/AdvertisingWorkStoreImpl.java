package com.msa.payment.domain.advertising.service.impl;

import com.msa.payment.domain.advertising.dto.response.AdvertisingWork;
import com.msa.payment.domain.advertising.repository.AdvertisingPaymentRepository;
import com.msa.payment.domain.advertising.service.*;
import com.msa.payment.domain.payment.dto.response.*;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

/** 승인 시작 이력과 취소 의도를 영속화한 뒤 임대를 획득합니다. */
@Service
@Transactional(timeout = 5)
public class AdvertisingWorkStoreImpl implements AdvertisingWorkStore {
    private final AdvertisingPaymentRepository payments;
    private final AdvertisingResultOutboxService results;
    private final Clock clock;

    public AdvertisingWorkStoreImpl(
            AdvertisingPaymentRepository payments,
            AdvertisingResultOutboxService results,
            Clock clock) {
        this.payments = payments;
        this.results = results;
        this.clock = clock;
    }

    public AdvertisingWork claim() {
        var p = payments.lockNextDue(clock.instant()).orElse(null);
        if (p == null) return null;
        if (!p.confirmationStarted
                && (p.cancellationRequested || !clock.instant().isBefore(p.expiresAt))) {
            p.status = "FAILED";
            p.providerState = "NOT_STARTED";
            p.lastFailureCode =
                    p.cancellationRequested
                            ? "CANCELED_BEFORE_APPROVAL"
                            : "APPROVAL_DEADLINE_PASSED";
            results.record(p, "AdvertisingPaymentStateChanged", null);
            return null;
        }
        boolean first = !p.confirmationStarted && p.approvalRequested && !p.cancellationRequested;
        if (first) p.confirmationStarted = true;
        p.leaseToken = UUID.randomUUID();
        p.leaseUntil = clock.instant().plusSeconds(30);
        p.attempts++;
        p.updatedAt = clock.instant();
        return new AdvertisingWork(
                p.id,
                p.leaseToken,
                TossPaymentRequest.advertising(
                        p.id,
                        p.amount,
                        p.expiresAt,
                        p.paymentKey,
                        first,
                        p.approvalRequested,
                        p.cancellationRequested));
    }

    public void complete(AdvertisingWork work, ProviderReply reply) {
        var p = payments.lock(work.paymentId()).orElse(null);
        if (p == null
                || !Objects.equals(p.leaseToken, work.leaseToken())
                || p.leaseUntil == null
                || !clock.instant().isBefore(p.leaseUntil)) return;
        String before = p.status;
        String failureBefore = p.lastFailureCode;
        p.leaseToken = null;
        p.leaseUntil = null;
        p.lastFailureCode = reply.failureCode();
        p.providerState = reply.status().name();
        if (!Set.of("FAILED", "CANCELED").contains(p.status)) {
            if (reply.status() == Status.APPROVED) {
                if (p.approvedAt == null || p.approvedAt.equals(reply.approvedAt())) {
                    p.status = "PAID";
                    p.approvedAt = reply.approvedAt();
                }
            } else if (reply.status() == Status.CANCELED && p.cancellationRequested) {
                p.status = "CANCELED";
                p.canceledAt = clock.instant();
                if (reply.approvedAt() != null) p.approvedAt = reply.approvedAt();
            } else if (reply.status() == Status.DECLINED && !"PAID".equals(p.status))
                p.status = "FAILED";
        }
        // PAID 원본은 불명 조회나 공급자 거절로 역행하지 않습니다.
        p.nextAttemptAt = clock.instant().plusSeconds(Math.min(60, 1L << Math.min(6, p.attempts)));
        if (p.cancellationRequested && "PAID".equals(p.status))
            p.nextAttemptAt = clock.instant().plusSeconds(2);
        p.updatedAt = clock.instant();
        if (!before.equals(p.status) || !Objects.equals(failureBefore, p.lastFailureCode))
            results.record(p, "AdvertisingPaymentStateChanged", null);
    }
}
