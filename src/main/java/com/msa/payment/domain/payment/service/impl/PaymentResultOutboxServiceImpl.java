package com.msa.payment.domain.payment.service.impl;

import com.msa.payment.domain.outbox.entity.OutboxEvent;
import com.msa.payment.domain.payment.entity.PaymentAttempt;
import com.msa.payment.domain.payment.service.PaymentResultOutboxService;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.*;

/** 결과와 원본 버전은 함께 커밋하며 토스 키·결제수단 개인정보를 결과 이벤트에서 제외합니다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentResultOutboxServiceImpl implements PaymentResultOutboxService {
    private final EntityManager em;
    private final JsonMapper json;
    private final Clock clock;

    public PaymentResultOutboxServiceImpl(EntityManager em, JsonMapper json, Clock clock) {
        this.em = em;
        this.json = json;
        this.clock = clock;
    }

    public void record(PaymentAttempt a) {
        UUID eventId = UUID.randomUUID();
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", eventId);
        event.put("schemaVersion", 1);
        event.put("type", "PAYMENT_RESULT");
        event.put("orderId", a.orderId);
        event.put("paymentAttemptId", a.id);
        event.put("amount", a.amount);
        event.put("version", ++a.resultVersion);
        event.put("status", a.status);
        event.put("approvedAt", a.approvedAt);
        event.put("occurredAt", clock.instant());
        em.persist(
                OutboxEvent.builder()
                        .id(eventId)
                        .topic("stockdrop.payment.results.private.v1")
                        .aggregateId(a.id)
                        .payload(json.writeValueAsString(event))
                        .now(clock.instant())
                        .build());
    }
}
