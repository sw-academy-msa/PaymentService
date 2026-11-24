package com.msa.payment.domain.membership.service.impl;

import com.msa.payment.domain.membership.entity.MembershipSubscription;
import com.msa.payment.domain.membership.service.MembershipResultOutboxService;
import com.msa.payment.domain.outbox.entity.OutboxEvent;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.*;

/** 키와 카드 정보를 제외하고 세대가 바뀌어도 증가하는 원본 버전을 발행합니다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class MembershipResultOutboxServiceImpl implements MembershipResultOutboxService {
    private final EntityManager em;
    private final JsonMapper json;
    private final Clock clock;

    public MembershipResultOutboxServiceImpl(EntityManager em, JsonMapper json, Clock clock) {
        this.em = em;
        this.json = json;
        this.clock = clock;
    }

    public void record(MembershipSubscription s, String type, long cycleNumber) {
        UUID id = UUID.randomUUID();
        var e = new LinkedHashMap<String, Object>();
        e.put("eventId", id);
        e.put("schemaVersion", 1);
        e.put("type", type);
        e.put("source", "PAYMENT");
        e.put("accountId", s.accountId);
        e.put("subscriptionId", s.id);
        e.put("generation", s.generation);
        e.put("aggregateVersion", ++s.aggregateVersion);
        e.put("cycleNumber", cycleNumber);
        e.put("state", s.state);
        e.put("autoRenew", s.autoRenew);
        e.put("periodStart", s.periodStart);
        e.put("periodEnd", s.periodEnd);
        e.put("entitlementUntil", s.entitlementUntil);
        e.put("graceUntil", s.graceUntil);
        e.put("amount", s.priceAmount);
        e.put("currency", s.currency);
        e.put("planVersion", s.planVersion);
        e.put("occurredAt", clock.instant());
        s.updatedAt = clock.instant();
        em.persist(
                OutboxEvent.builder()
                        .id(id)
                        .topic("stockdrop.membership.events.private.v1")
                        .aggregateId(s.accountId)
                        .payload(json.writeValueAsString(e))
                        .now(clock.instant())
                        .build());
    }
}
