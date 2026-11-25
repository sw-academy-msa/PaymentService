package com.msa.payment.domain.analytics.service.impl;

import com.msa.payment.domain.analytics.dto.response.GaWork;
import com.msa.payment.domain.analytics.entity.GaDispatch;
import com.msa.payment.domain.analytics.repository.GaDispatchRepository;
import com.msa.payment.domain.analytics.service.GaDispatchStore;
import com.msa.payment.domain.payment.entity.PaymentAttempt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Clock;
import java.util.UUID;

/** 플래그 OFF에는 전송 의도를 만들지 않고 분석 장애를 결제 재시도로 전파하지 않습니다. */
@Service
@Transactional(timeout = 5)
public class GaDispatchStoreImpl implements GaDispatchStore {
    private final GaDispatchRepository rows;
    private final EntityManager em;
    private final Clock clock;
    private final boolean enabled;
    private final Counter failed;

    public GaDispatchStoreImpl(
            GaDispatchRepository rows,
            EntityManager em,
            Clock clock,
            @Value("${payment.ga.enabled:false}") boolean enabled,
            MeterRegistry registry) {
        this.failed = registry.counter("stockdrop.payment.ga.failed");
        this.rows = rows;
        this.em = em;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(PaymentAttempt a) {
        if (!enabled || a.analyticsPayload == null) return;
        String kind =
                a.status == PaymentAttempt.Status.APPROVED
                        ? "PURCHASE"
                        : a.status == PaymentAttempt.Status.CANCELED && a.approvedAt != null
                                ? "REFUND"
                                : null;
        if (kind == null || rows.existsById(new GaDispatch.Key(a.orderId, kind))) return;
        em.persist(
                GaDispatch.builder()
                        .orderId(a.orderId)
                        .kind(kind)
                        .payload(a.analyticsPayload)
                        .amount(a.amount)
                        .now(clock.instant())
                        .build());
    }

    public GaWork claim() {
        var d = rows.next(clock.instant()).orElse(null);
        if (d == null) return null;
        d.leaseToken = UUID.randomUUID();
        d.leaseUntil = clock.instant().plusSeconds(120);
        return new GaWork(d.orderId, d.kind, d.payload, d.amount, d.leaseToken, d.createdAt);
    }

    public void complete(GaWork work, String result) {
        var d = rows.lock(work.orderId(), work.kind()).orElse(null);
        if (d == null
                || !work.token().equals(d.leaseToken)
                || d.leaseUntil == null
                || !clock.instant().isBefore(d.leaseUntil)) return;
        d.leaseToken = null;
        d.leaseUntil = null;
        if ("RETRY".equals(result)) {
            d.attempts++;
            if (d.attempts <= 3) {
                d.nextAttemptAt =
                        clock.instant().plusSeconds(new long[] {60, 300, 1800}[d.attempts - 1]);
                return;
            }
            result = "FAILED";
        }
        if ("FAILED".equals(result)) failed.increment();
        d.status = GaDispatch.Status.valueOf(result);
        d.finishedAt = clock.instant();
    }

    public void purge() {
        rows.purge(clock.instant().minusSeconds(7 * 86400));
        em.createQuery(
                        "update PaymentAttempt a set a.analyticsPayload=null where a.createdAt <"
                                + " :cutoff")
                .setParameter("cutoff", clock.instant().minusSeconds(7 * 86400))
                .executeUpdate();
    }
}
