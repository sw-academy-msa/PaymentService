package com.msa.payment.domain.membership.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.msa.payment.global.config.database.PaymentDatabaseTestSupport;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 실제 Kafka와 DB로 해지 중복 수신 및 영구 오류와 DB 장애의 DLT 격리를 확인합니다. */
@SpringBootTest(properties = "membership.consumer-enabled=true")
@ActiveProfiles("test")
class MembershipMessagingIntegrationTest extends PaymentDatabaseTestSupport {
    static final String TOPIC = "stockdrop.membership.commands.private.v1";
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(TOPIC, 2, (short) 1),
                                    new NewTopic(TOPIC + ".DLT", 2, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    private Map<String, Object> command(UUID account, UUID subscription) {
        var c = new LinkedHashMap<String, Object>();
        c.put("eventId", UUID.randomUUID());
        c.put("schemaVersion", 1);
        c.put("type", "MEMBERSHIP_CANCEL_REQUESTED");
        c.put("accountId", account);
        c.put("subscriptionId", subscription);
        c.put("generation", 1);
        c.put("commandVersion", 1);
        c.put("occurredAt", Instant.parse("2030-01-01T00:00:00Z"));
        return c;
    }

    private UUID subscription(UUID account) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into payment.membership_subscriptions
                (id,account_id,generation,state,auto_renew,plan_version,price_amount,currency,anchor_day,
                 zone,aggregate_version,row_version,terms_version,agreed_at,agreed_account_id,created_at,updated_at)
                values (?,?,1,'PENDING',true,'monthly-v1',4900,'KRW',1,'Asia/Seoul',0,0,'recurring-v1',now(),?,now(),now())
                """,
                id,
                account,
                account);
        return id;
    }

    private void send(UUID account, Map<String, Object> command) throws Exception {
        kafka.send(TOPIC, account.toString(), json.writeValueAsBytes(command))
                .get(10, TimeUnit.SECONDS);
    }

    private void dlt(Map<String, Object> command) {
        try (var consumer =
                new KafkaConsumer<String, byte[]>(
                        Map.of(
                                "bootstrap.servers",
                                KAFKA.getBootstrapServers(),
                                "group.id",
                                "membership-test-" + UUID.randomUUID(),
                                "auto.offset.reset",
                                "earliest"),
                        new StringDeserializer(),
                        new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            await().atMost(Duration.ofSeconds(25))
                    .until(
                            () -> {
                                for (var record : consumer.poll(Duration.ofMillis(300))) {
                                    Map<?, ?> body = json.readValue(record.value(), Map.class);
                                    if (command.get("eventId")
                                            .toString()
                                            .equals(body.get("eventId"))) return true;
                                }
                                return false;
                            });
        }
    }

    @Test
    void duplicateCancellationCommitsOneReceiptAndResult() throws Exception {
        UUID account = UUID.randomUUID();
        UUID subscription = subscription(account);
        var c = command(account, subscription);
        send(account, c);
        send(account, c);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "select count(*) from"
                                                            + " payment.membership_command_receipts"
                                                            + " where account_id=?",
                                                    Long.class,
                                                    account))
                                    .isEqualTo(1);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "select aggregate_version from"
                                                        + " payment.membership_subscriptions where"
                                                        + " id=?",
                                                    Long.class,
                                                    subscription))
                                    .isEqualTo(1);
                        });
        c.put("commandVersion", 2);
        send(account, c);
        dlt(c);
        assertThat(
                        jdbc.queryForObject(
                                "select aggregate_version from payment.membership_subscriptions"
                                        + " where id=?",
                                Long.class,
                                subscription))
                .isEqualTo(1);
    }

    @Test
    void databaseFailureIsBoundedAndDeadLetteredWithoutReceipt() throws Exception {
        UUID account = UUID.randomUUID();
        var c = command(account, subscription(account));
        jdbc.execute(
                "alter table payment.membership_command_receipts rename to"
                        + " membership_receipts_unavailable");
        try {
            send(account, c);
            dlt(c);
        } finally {
            jdbc.execute(
                    "alter table payment.membership_receipts_unavailable rename to"
                            + " membership_command_receipts");
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment.membership_command_receipts where"
                                        + " account_id=?",
                                Long.class,
                                account))
                .isZero();
    }
}
