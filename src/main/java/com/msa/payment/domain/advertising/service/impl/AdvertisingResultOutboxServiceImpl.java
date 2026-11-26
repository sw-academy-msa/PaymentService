package com.msa.payment.domain.advertising.service.impl;

import com.msa.payment.domain.advertising.entity.AdvertisingPayment;
import com.msa.payment.domain.advertising.service.AdvertisingResultOutboxService;
import com.msa.payment.domain.outbox.entity.OutboxEvent;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.*;

/** 공급자 결제키를 제외한 완전 스냅샷과 증가하는 결과 버전을 발행합니다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AdvertisingResultOutboxServiceImpl implements AdvertisingResultOutboxService {
    private final EntityManager em;
    private final JsonMapper json;
    private final Clock clock;

    public AdvertisingResultOutboxServiceImpl(EntityManager em, JsonMapper json, Clock clock) {
        this.em = em;
        this.json = json;
        this.clock = clock;
    }

    public void record(AdvertisingPayment p, String type, UUID cause) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("applicationId", p.applicationId);
        payload.put("submissionId", p.submissionId);
        payload.put("paymentId", p.id);
        payload.put("productId", p.productId);
        payload.put("sellerId", p.sellerId);
        payload.put("amount", p.amount);
        payload.put("currency", p.currency);
        payload.put("quoteHash", p.quoteHash);
        payload.put("formulaVersion", p.formulaVersion);
        payload.put("paymentStatus", p.status);
        payload.put("resultVersion", ++p.resultVersion);
        payload.put("cancellationRequested", p.cancellationRequested);
        payload.put("approvedAt", p.approvedAt);
        payload.put("canceledAt", p.canceledAt);
        payload.put("failureCode", p.lastFailureCode);
        UUID id = UUID.randomUUID();
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", id);
        event.put("eventType", type);
        event.put("schemaVersion", 1);
        event.put("aggregateId", p.applicationId);
        event.put("aggregateVersion", p.resultVersion);
        event.put("occurredAt", clock.instant());
        event.put("correlationId", p.applicationId);
        event.put("causationId", cause);
        event.put("payload", payload);
        p.updatedAt = clock.instant();
        em.persist(
                OutboxEvent.builder()
                        .id(id)
                        .topic("payment.advertising.results.v1")
                        .aggregateId(p.applicationId)
                        .payload(json.writeValueAsString(event))
                        .now(clock.instant())
                        .build());
    }
}
