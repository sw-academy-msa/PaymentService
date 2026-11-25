package com.msa.payment.domain.membership.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;

/** 실제 PostgreSQL에서 중복 청구와 멱등 키 재사용을 DB 제약이 거절하는지 검사합니다. */
@SpringBootTest(
        properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
class MembershipFinancialSchemaIntegrationTest {
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MembershipSubscriptionRepository subscriptions;

    private UUID subscription() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into payment.membership_subscriptions
                (id,account_id,generation,state,auto_renew,plan_version,price_amount,currency,anchor_day,
                 zone,aggregate_version,row_version,terms_version,agreed_at,agreed_account_id,created_at,updated_at)
                values (?, ?, 1, 'PENDING', true, 'monthly-v1',4900,'KRW',1,
                'Asia/Seoul',0,0,'recurring-v1',now(),?,now(),now())
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID());
        return id;
    }

    private UUID cycle(UUID subscription) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into payment.membership_billing_cycles
                (id,subscription_id,generation,cycle_number,scheduled_at,amount,currency,status,retry_number,created_at,updated_at)
                values (?,?,1,1,now(),4900,'KRW','PENDING',0,now(),now())
                """,
                id,
                subscription);
        return id;
    }

    private void attempt(UUID cycle, int number, String order, String key, String payment) {
        jdbc.update(
                """
                insert into payment.membership_billing_attempts
                (id,cycle_id,attempt_number,order_id,idempotency_key,status,amount,currency,request_hash,
                 payment_key,provider_key_version,next_attempt_at,transport_attempts,created_at,updated_at)
                values (?,?,?,?,?,'PENDING',4900,'KRW',?,?, 'v1',now(),0,now(),now())
                """,
                UUID.randomUUID(),
                cycle,
                number,
                order,
                key,
                "a".repeat(64),
                payment);
    }

    @Test
    void duplicateCycleAndAttemptIdentityAreRejected() {
        UUID sub = subscription();
        UUID cycle = cycle(sub);
        assertThatThrownBy(() -> cycle(sub))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        String order = "sdm-" + UUID.randomUUID(), key = "sdm-bill-" + UUID.randomUUID();
        attempt(cycle, 0, order, key, "payment-" + UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                attempt(
                                        cycle,
                                        0,
                                        "other-" + UUID.randomUUID(),
                                        UUID.randomUUID().toString(),
                                        null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> attempt(cycle, 1, order, UUID.randomUUID().toString(), null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> attempt(cycle, 1, "other-" + UUID.randomUUID(), key, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rowLockAndAggregateVersionSurviveGenerationChange() {
        UUID id = subscription();
        tx.executeWithoutResult(
                status -> {
                    var s = subscriptions.lock(id).orElseThrow();
                    s.aggregateVersion = 11;
                    s.generation = 2;
                });
        var stored = subscriptions.findById(id).orElseThrow();
        assertThat(stored.aggregateVersion).isEqualTo(11);
        assertThat(stored.generation).isEqualTo(2);
        assertThat(stored.rowVersion).isEqualTo(1);
    }
}
