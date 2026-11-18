package com.msa.payment.domain.withdrawal.service.impl;

import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.domain.payment.repository.PaymentAttemptRepository;
import com.msa.payment.domain.withdrawal.dto.request.PaymentClearanceRequest;
import com.msa.payment.domain.withdrawal.dto.response.PaymentClearanceResponse;
import com.msa.payment.domain.withdrawal.service.PaymentClearanceService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

/** Payment는 accountId를 소유하지 않으므로 추정 JOIN 대신 Order가 제공한 원본 참조를 검증합니다. */
@Service
@Transactional(readOnly = true)
public class PaymentClearanceServiceImpl implements PaymentClearanceService {
    private final PaymentAttemptRepository attempts;
    private final Clock clock;

    public PaymentClearanceServiceImpl(PaymentAttemptRepository attempts, Clock clock) {
        this.attempts = attempts;
        this.clock = clock;
    }

    public PaymentClearanceResponse inspect(PaymentClearanceRequest request) {
        long pending = 0;
        var seen = new HashSet<UUID>();
        for (var ref : request.references()) {
            var attempt = attempts.findById(ref.paymentAttemptId());
            if (!seen.add(ref.paymentAttemptId()) || attempt.isEmpty()) {
                pending++;
                continue;
            }
            var a = attempt.get();
            boolean working = a.leaseUntil != null && clock.instant().isBefore(a.leaseUntil);
            boolean terminal =
                    ref.approved()
                            ? a.status == Status.APPROVED && !a.cancellationRequested
                            : (a.status == Status.CANCELED || a.status == Status.DECLINED);
            if (!a.orderId.equals(ref.orderId()) || working || !terminal) pending++;
        }
        return PaymentClearanceResponse.builder().pendingPayments(pending).build();
    }
}
