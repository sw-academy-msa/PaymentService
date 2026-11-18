package com.msa.payment.domain.payment.service.impl;

import com.msa.payment.domain.payment.dto.response.*;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.domain.payment.repository.PaymentAttemptRepository;
import com.msa.payment.domain.payment.service.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** 복제본의 외부 호출을 임대로 분배합니다. 임대 만료 후 재실행도 동일 토스 멱등 키를 사용합니다. */
@Service
@Transactional(timeout = 5)
public class PaymentWorkStoreImpl implements PaymentWorkStore {
    private final PaymentAttemptRepository attempts;
    private final Clock clock;
    private final PaymentResultOutboxService results;

    public PaymentWorkStoreImpl(
            PaymentAttemptRepository attempts, Clock clock, PaymentResultOutboxService results) {
        this.attempts = attempts;
        this.clock = clock;
        this.results = results;
    }

    public PaymentWork claim() {
        var now = clock.instant();
        var found = attempts.lockNextDue(now);
        if (found.isEmpty()) return null;
        var a = found.get();
        if (a.approvalRequested
                && !a.cancellationRequested
                && !a.confirmationStarted
                && !now.isBefore(a.expiresAt)) {
            // 승인 전 기한 초과만 로컬에서 확정 실패할 수 있습니다. 승인 시작 이력이 있으면 반드시 조회합니다.
            a.status = Status.DECLINED;
            a.lastFailureCode = "APPROVAL_NOT_STARTED_BEFORE_DEADLINE";
            results.record(a);
            return null;
        }
        boolean first = a.approvalRequested && !a.cancellationRequested && !a.confirmationStarted;
        if (first) a.confirmationStarted = true;
        a.leaseToken = UUID.randomUUID();
        a.leaseUntil = now.plusSeconds(30);
        a.attempts++;
        return PaymentWork.builder()
                .attemptId(a.id)
                .orderId(a.orderId)
                .amount(a.amount)
                .expiresAt(a.expiresAt)
                .paymentKey(a.paymentKey)
                .leaseToken(a.leaseToken)
                .firstApproval(first)
                .approvalRequested(a.approvalRequested)
                .cancellationRequested(a.cancellationRequested)
                .build();
    }

    public void complete(PaymentWork work, ProviderReply reply) {
        var a = attempts.lock(work.attemptId()).orElse(null);
        if (a == null
                || !Objects.equals(a.leaseToken, work.leaseToken())
                || a.leaseUntil == null
                || !clock.instant().isBefore(a.leaseUntil)) return;
        // 만료된 작업 결과는 버리고 새 임대 작업이 같은 멱등 키로 복구하도록 합니다.
        var previousStatus = a.status;
        var previousApprovedAt = a.approvedAt;
        var previousFailureCode = a.lastFailureCode;
        // 승인 원본은 불명·실패로 역행시키지 않습니다. 취소는 Order의 영속 보상 의도가 있을 때만 반영합니다.
        boolean permitted =
                a.status != Status.APPROVED
                        || reply.status() == Status.APPROVED
                        || (reply.status() == Status.CANCELED && a.cancellationRequested);
        if (a.status != Status.CANCELED && a.status != Status.DECLINED && permitted) {
            if (reply.status() != Status.APPROVED
                    || a.approvedAt == null
                    || a.approvedAt.equals(reply.approvedAt())) {
                a.status = reply.status();
                if (reply.approvedAt() != null) a.approvedAt = reply.approvedAt();
            }
        }
        a.lastFailureCode = reply.failureCode();
        a.leaseToken = null;
        a.leaseUntil = null;
        a.nextAttemptAt = clock.instant().plusSeconds(Math.min(60, 1L << Math.min(6, a.attempts)));
        if (a.cancellationRequested && a.status == Status.APPROVED)
            a.nextAttemptAt = clock.instant().plusSeconds(2);
        // 재조회가 같은 UNKNOWN을 반복할 때 결과 버전·Outbox를 불필요하게 늘리지 않습니다.
        if (previousStatus != a.status
                || !Objects.equals(previousApprovedAt, a.approvedAt)
                || !Objects.equals(previousFailureCode, a.lastFailureCode)) {
            results.record(a);
        }
    }
}
