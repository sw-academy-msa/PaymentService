package com.msa.payment.domain.analytics.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.payment.domain.analytics.entity.GaDispatch;
import com.msa.payment.domain.analytics.repository.GaDispatchRepository;
import com.msa.payment.domain.analytics.service.*;
import com.msa.payment.domain.payment.entity.PaymentAttempt;
import com.msa.payment.global.config.database.PaymentDatabaseTestSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/** 실제 PostgreSQL에서 종류별 멱등, 재시도 상한과 오래된 임대 무효화를 검증합니다. */
@SpringBootTest(
        properties = {
            "payment.ga.enabled=true",
            "payment.ga.measurement-id=G-TEST",
            "payment.ga.api-secret=fixture-secret",
            "payment.ga.poll-ms=86400000"
        })
@ActiveProfiles("test")
@Import(PaymentDatabaseTestSupport.Time.class)
class GaDispatchStoreIntegrationTest extends PaymentDatabaseTestSupport {
    @MockitoBean GaProcessor processor;
    @Autowired GaDispatchStore store;
    @Autowired GaDispatchRepository rows;
    @Autowired MutableClock clock;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void setup() {
        rows.deleteAll();
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
    }

    private PaymentAttempt approved() {
        var a =
                PaymentAttempt.builder()
                        .id(UUID.randomUUID())
                        .order(UUID.randomUUID())
                        .amount(27000)
                        .expiresAt(clock.instant().plusSeconds(300))
                        .now(clock.instant())
                        .build();
        a.status = PaymentAttempt.Status.APPROVED;
        a.approvedAt = clock.instant();
        a.analyticsPayload = "{}";
        return a;
    }

    @Test
    void duplicateKindsAndStaleLeaseAreSafeAndRefundWaitsForPurchase() {
        var a = approved();
        tx.executeWithoutResult(
                s -> {
                    store.record(a);
                    store.record(a);
                });
        assertThat(rows.count()).isEqualTo(1);
        var old = store.claim();
        assertThat(store.claim()).isNull();
        clock.set(clock.instant().plusSeconds(121));
        var current = store.claim();
        store.complete(old, "SENT");
        assertThat(rows.findById(new GaDispatch.Key(a.orderId, "PURCHASE")).orElseThrow().status)
                .isEqualTo(GaDispatch.Status.PENDING);
        a.status = PaymentAttempt.Status.CANCELED;
        tx.executeWithoutResult(
                s -> {
                    store.record(a);
                    store.record(a);
                });
        assertThat(rows.count()).isEqualTo(2);
        assertThat(store.claim()).isNull();
        store.complete(current, "SENT");
        assertThat(store.claim().kind()).isEqualTo("REFUND");
    }

    @Test
    void threeRetriesThenFailureAndPayloadRetention() {
        var a = approved();
        tx.executeWithoutResult(s -> store.record(a));
        for (long delay : new long[] {60, 300, 1800}) {
            var work = store.claim();
            store.complete(work, "RETRY");
            assertThat(store.claim()).isNull();
            clock.set(clock.instant().plusSeconds(delay));
        }
        store.complete(store.claim(), "RETRY");
        var id = new GaDispatch.Key(a.orderId, "PURCHASE");
        assertThat(rows.findById(id).orElseThrow().status).isEqualTo(GaDispatch.Status.FAILED);
        clock.set(clock.instant().plusSeconds(7 * 86400 + 1));
        store.purge();
        assertThat(rows.findById(id).orElseThrow().payload).isNull();
    }
}
