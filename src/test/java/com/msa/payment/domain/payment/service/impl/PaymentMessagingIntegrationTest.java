package com.msa.payment.domain.payment.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.msa.payment.domain.outbox.service.OutboxPublisher;
import com.msa.payment.domain.payment.dto.request.PaymentCommand;
import com.msa.payment.domain.payment.dto.response.ProviderReply;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.domain.payment.service.*;
import com.msa.payment.global.config.database.PaymentDatabaseTestSupport;
import com.sun.net.httpserver.HttpServer;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 실제 Kafka/PostgreSQL과 HTTP 공급자 대역으로 중복·타임아웃·임대·Outbox 복구를 검증합니다. 실제 토스 결제는 아닙니다. */
@SpringBootTest(
        properties = {
            "payment.messaging.enabled=true",
            "payment.outbox.publisher-enabled=true",
            "payment.outbox.poll-ms=3600000",
            "payment.processing.enabled=false"
        })
@ActiveProfiles("test")
@Import(PaymentDatabaseTestSupport.Time.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentMessagingIntegrationTest extends PaymentDatabaseTestSupport {
    static final String COMMAND = "stockdrop.payment.commands.private.v1";
    static final String RESULT = "stockdrop.payment.results.private.v1";
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    static final ConcurrentMap<String, Trade> TRADES = new ConcurrentHashMap<>();
    static final HttpServer TOSS;
    static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    static class Trade {
        final UUID order;
        volatile String status = "IN_PROGRESS";
        volatile Instant approvedAt;
        final AtomicInteger approves = new AtomicInteger(),
                cancels = new AtomicInteger(),
                queries = new AtomicInteger();
        volatile boolean loseApprove, loseCancel, wrongAmount;
        final Set<String> approvalKeys = ConcurrentHashMap.newKeySet(),
                cancelKeys = ConcurrentHashMap.newKeySet();

        Trade(UUID order) {
            this.order = order;
        }
    }

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(COMMAND, 2, (short) 1),
                                    new NewTopic(COMMAND + ".DLT", 2, (short) 1),
                                    new NewTopic(RESULT, 2, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
            TOSS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TOSS.setExecutor(EXECUTOR);
            TOSS.createContext(
                    "/",
                    e -> {
                        try {
                            var path = e.getRequestURI().getPath();
                            Map<?, ?> body =
                                    e.getRequestMethod().equals("POST")
                                            ? JSON.readValue(
                                                    e.getRequestBody().readAllBytes(), Map.class)
                                            : Map.of();
                            String key =
                                    path.equals("/v1/payments/confirm")
                                            ? (String) body.get("paymentKey")
                                            : path.substring("/v1/payments/".length())
                                                    .replace("/cancel", "");
                            var trade = TRADES.get(key);
                            String expected =
                                    "Basic "
                                            + Base64.getEncoder()
                                                    .encodeToString(
                                                            "test_gsk_synthetic:"
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8));
                            if (!expected.equals(e.getRequestHeaders().getFirst("Authorization"))) {
                                e.sendResponseHeaders(401, -1);
                                return;
                            }
                            if (trade == null) {
                                e.sendResponseHeaders(404, -1);
                                return;
                            }
                            if (path.equals("/v1/payments/confirm")) {
                                if (!("SD-" + trade.order).equals(body.get("orderId"))
                                        || ((Number) body.get("amount")).longValue() != 6000) {
                                    e.sendResponseHeaders(400, -1);
                                    return;
                                }
                                trade.approves.incrementAndGet();
                                trade.approvalKeys.add(
                                        e.getRequestHeaders().getFirst("Idempotency-Key"));
                                trade.status = "DONE";
                                trade.approvedAt = Instant.parse("2030-01-01T00:00:00Z");
                                if (trade.loseApprove) {
                                    trade.loseApprove = false;
                                    try {
                                        Thread.sleep(1500);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            } else if (path.endsWith("/cancel")) {
                                trade.cancels.incrementAndGet();
                                trade.cancelKeys.add(
                                        e.getRequestHeaders().getFirst("Idempotency-Key"));
                                trade.status = "CANCELED";
                                if (trade.loseCancel) {
                                    trade.loseCancel = false;
                                    try {
                                        Thread.sleep(1500);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            } else trade.queries.incrementAndGet();
                            var response = new LinkedHashMap<String, Object>();
                            response.put("paymentKey", key);
                            response.put("orderId", "SD-" + trade.order);
                            response.put("totalAmount", trade.wrongAmount ? 7000 : 6000);
                            response.put(
                                    "balanceAmount", trade.status.equals("CANCELED") ? 0 : 6000);
                            response.put("currency", "KRW");
                            response.put("status", trade.status);
                            response.put("approvedAt", trade.approvedAt);
                            byte[] bytes = JSON.writeValueAsBytes(response);
                            e.getResponseHeaders().set("Content-Type", "application/json");
                            e.sendResponseHeaders(200, bytes.length);
                            e.getResponseBody().write(bytes);
                        } catch (Exception ignored) {
                            /* 응답 유실 대역의 원문·인증 헤더는 기록하지 않습니다. */
                        } finally {
                            e.close();
                        }
                    });
            TOSS.start();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry p) {
        p.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        p.add("spring.kafka.listener.auto-startup", () -> "true");
        p.add("spring.kafka.consumer.group-id", () -> "payment-integration");
        p.add("payment.toss.enabled", () -> "true");
        p.add("payment.toss.secret-key", () -> "test_gsk_synthetic");
        p.add("payment.toss.client-key", () -> "test_gck_synthetic");
        p.add("payment.toss.base-url", () -> "http://127.0.0.1:" + TOSS.getAddress().getPort());
        p.add("payment.toss.allow-insecure-local", () -> "true");
        p.add("payment.toss.request-timeout", () -> "1s");
    }

    @Autowired PaymentCommandService commands;
    @Autowired PaymentProcessor processor;
    @Autowired PaymentWorkStore store;

    // 직접 발행을 검증하는 동안 초기 자동 실행이 같은 Outbox 행을 선점하지 않도록 격리합니다.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.msa.payment.domain.outbox.scheduling.OutboxPublishJob automaticPublisher;

    @Autowired OutboxPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired org.springframework.kafka.listener.DefaultErrorHandler errors;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    UUID order, attempt;
    String key;
    Trade trade;

    @BeforeEach
    void prepare() {
        jdbc.update("update payment.payment_attempts set next_attempt_at='2099-01-01T00:00:00Z'");
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        order = UUID.randomUUID();
        attempt = UUID.randomUUID();
        key = "synthetic-" + attempt;
        trade = new Trade(order);
        TRADES.put(key, trade);
    }

    @AfterAll
    void stop() {
        registry.stop();
        TOSS.stop(0);
        EXECUTOR.shutdownNow();
    }

    PaymentCommand command(String action, long version) {
        return new PaymentCommand(
                UUID.randomUUID(),
                1,
                "PAYMENT_COMMAND",
                action,
                order,
                attempt,
                version,
                clock.instant(),
                6000,
                Instant.parse("2030-01-01T00:05:00Z"),
                action.equals("CANCEL"),
                action.equals("APPROVE") ? key : null);
    }

    void receive(PaymentCommand c) {
        commands.receive(attempt.toString(), JSON.writeValueAsBytes(c));
    }

    void send(PaymentCommand c) throws Exception {
        kafka.send(COMMAND, attempt.toString(), JSON.writeValueAsBytes(c))
                .get(20, TimeUnit.SECONDS);
    }

    String status() {
        // Kafka 저장이 아직 시작되지 않은 정상 대기 구간도 Awaitility가 재확인하게 합니다.
        var rows =
                jdbc.queryForList(
                        "select status from payment.payment_attempts where id=?",
                        String.class,
                        attempt);
        return rows.isEmpty() ? "ABSENT" : rows.getFirst();
    }

    void due() {
        clock.set(clock.instant().plusSeconds(65));
    }

    long count(String table) {
        return jdbc.queryForObject("select count(*) from payment." + table, Long.class);
    }

    @Test
    void actualKafkaCommandPersistsBeforeHttpAndResultUsesOutbox() throws Exception {
        send(command("APPROVE", 1));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(status()).isEqualTo("PENDING"));
        assertThat(trade.approves.get()).isZero();
        assertThat(processor.processNext()).isTrue();
        assertThat(status()).isEqualTo("APPROVED");
        while (publisher.publishNext()) {
            /* 실제 Kafka 결과를 확인합니다. */
        }
        try (var consumer = consumer(RESULT)) {
            boolean approved = false;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!approved && System.nanoTime() < end)
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.key().equals(attempt.toString())) {
                        String value = new String(r.value(), StandardCharsets.UTF_8);
                        assertThat(value).doesNotContain("paymentKey", "test_gsk", "synthetic-");
                        approved |= JSON.readTree(value).path("status").asText().equals("APPROVED");
                        if (JSON.readTree(value).path("status").asText().equals("APPROVED")) {
                            var fixture =
                                    java.nio.file.Path.of(
                                            "build/generated/payment-approved-result.json");
                            java.nio.file.Files.createDirectories(fixture.getParent());
                            java.nio.file.Files.writeString(fixture, value);
                        }
                    }
                }
            assertThat(approved).isTrue();
        }
        assertThat(trade.approvalKeys).containsExactly("stockdrop-approve-" + attempt);
    }

    @Test
    void publicClientConfigurationNeverReturnsSecretKey() throws Exception {
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/v1/payments/client-configuration")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.jwt()
                                                .authorities(
                                                        new org.springframework.security.core
                                                                .authority.SimpleGrantedAuthority(
                                                                "ROLE_BUYER"))))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.success")
                                .value(true))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.code")
                                .value(com.msa.payment.global.response.SuccessCode.OK.code()))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.message")
                                .value(com.msa.payment.global.response.SuccessCode.OK.message()))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.data.clientKey")
                                .value("test_gck_synthetic"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("test_gsk"))));
    }

    @Test
    void duplicateCommandDoesNotApproveTwiceAndConflictingIdGoesToDlt() throws Exception {
        var c = command("APPROVE", 1);
        receive(c);
        receive(c);
        processor.processNext();
        receive(c);
        assertThat(trade.approves.get()).isEqualTo(1);
        var bad =
                new PaymentCommand(
                        c.eventId(),
                        1,
                        c.type(),
                        c.action(),
                        c.orderId(),
                        c.paymentAttemptId(),
                        1,
                        c.occurredAt(),
                        7000,
                        c.expiresAt(),
                        false,
                        key);
        send(bad);
        try (var consumer = consumer(COMMAND + ".DLT")) {
            boolean found = false;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!found && System.nanoTime() < end)
                for (var r : consumer.poll(Duration.ofMillis(500)))
                    found |= r.key().equals(attempt.toString());
            assertThat(found).isTrue();
        }
        assertThat(status()).isEqualTo("APPROVED");
    }

    @Test
    void checkBeforeApproveDoesNotDiscardOlderApprovalCommand() {
        receive(command("CHECK", 2));
        processor.processNext();
        assertThat(status()).isEqualTo("UNKNOWN");
        receive(command("APPROVE", 1));
        processor.processNext();
        assertThat(status()).isEqualTo("APPROVED");
    }

    @Test
    void cancellationBeforeApprovalPermanentlyFencesLateCommand() {
        receive(command("CANCEL", 3));
        receive(command("APPROVE", 1));
        assertThat(processor.processNext()).isFalse();
        assertThat(status()).isEqualTo("CANCELED");
        assertThat(trade.approves.get()).isZero();
    }

    @Test
    void firstApprovalAfterDeadlineNeverCallsToss() {
        receive(command("APPROVE", 1));
        clock.set(clock.instant().plusSeconds(300));
        processor.processNext();
        assertThat(status()).isEqualTo("DECLINED");
        assertThat(trade.approves.get()).isZero();
    }

    @Test
    void lostApprovalResponseRecoversAfterDeadlineWithoutApprovingAgain() {
        trade.loseApprove = true;
        receive(command("APPROVE", 1));
        processor.processNext();
        assertThat(status()).isEqualTo("UNKNOWN");
        clock.set(clock.instant().plusSeconds(301));
        processor.processNext();
        assertThat(status()).isEqualTo("APPROVED");
        assertThat(trade.approves.get()).isEqualTo(1);
        assertThat(trade.queries.get()).isGreaterThan(0);
    }

    @Test
    void uncertainCancellationKeepsApprovedUntilActualCancellationIsVerified() {
        receive(command("APPROVE", 1));
        processor.processNext();
        trade.loseCancel = true;
        receive(command("CANCEL", 2));
        processor.processNext();
        assertThat(status()).isEqualTo("APPROVED");
        due();
        processor.processNext();
        assertThat(status()).isEqualTo("CANCELED");
        assertThat(trade.cancels.get()).isEqualTo(1);
        assertThat(trade.cancelKeys).containsExactly("stockdrop-cancel-" + attempt);
    }

    @Test
    void expiredWorkerLeaseRecoversWithSameIdempotencyKeyAndRejectsStaleResult() {
        receive(command("APPROVE", 1));
        var stale = store.claim();
        assertThat(stale).isNotNull();
        assertThat(store.claim()).isNull();
        clock.set(clock.instant().plusSeconds(31));
        processor.processNext();
        assertThat(status()).isEqualTo("APPROVED");
        store.complete(stale, new ProviderReply(Status.DECLINED, null, null, null));
        assertThat(status()).isEqualTo("APPROVED");
    }

    @Test
    void wrongProviderAmountAndNotFoundCannotBecomeSuccessfulOrDefinitivelyFailed() {
        trade.wrongAmount = true;
        receive(command("APPROVE", 1));
        processor.processNext();
        assertThat(status()).isEqualTo("UNKNOWN");
        TRADES.remove(key);
        clock.set(clock.instant().plusSeconds(301));
        processor.processNext();
        assertThat(status()).isEqualTo("UNKNOWN");
    }

    @Test
    void commandReceiptAndInitialResultOutboxRollbackAtomically() {
        long receipts = count("payment_command_receipts"), outbox = count("outbox_events");
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        s -> {
                                            receive(command("APPROVE", 1));
                                            throw new IllegalStateException("rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("payment_command_receipts")).isEqualTo(receipts);
        assertThat(count("outbox_events")).isEqualTo(outbox);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment.payment_attempts where id=?",
                                Long.class,
                                attempt))
                .isZero();
    }

    @Test
    void resultAndOutboxRollbackTogether() {
        receive(command("APPROVE", 1));
        var work = store.claim();
        long outbox = count("outbox_events");
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        s -> {
                                            store.complete(
                                                    work,
                                                    new ProviderReply(
                                                            Status.APPROVED,
                                                            clock.instant(),
                                                            key,
                                                            null));
                                            throw new IllegalStateException("rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(count("outbox_events")).isEqualTo(outbox);
    }

    @Test
    void concurrentWorkersClaimOneAttemptOnly() throws Exception {
        receive(command("APPROVE", 1));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var claims = pool.invokeAll(List.of(store::claim, store::claim));
            long count = 0;
            for (var result : claims) if (result.get() != null) count++;
            assertThat(count).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void databaseFailureRetainsKafkaCommandUntilReceiptCanBeCommitted() throws Exception {
        var failures = new AtomicInteger();
        errors.setRetryListeners((record, error, delivery) -> failures.incrementAndGet());
        jdbc.execute(
                "alter table payment.payment_command_receipts rename to"
                        + " payment_receipts_unavailable");
        try {
            send(command("APPROVE", 1));
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(failures.get()).isGreaterThan(0));
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from payment.payment_attempts where id=?",
                                    Long.class,
                                    attempt))
                    .isZero();
        } finally {
            jdbc.execute(
                    "alter table payment.payment_receipts_unavailable rename to"
                            + " payment_command_receipts");
        }
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(status()).isEqualTo("PENDING"));
        assertThat(trade.approves.get()).isZero();
    }

    @Test
    void retryCannotChangeOriginalDeadlineAmountOrKey() {
        receive(command("APPROVE", 1));
        var changed =
                new PaymentCommand(
                        UUID.randomUUID(),
                        1,
                        "PAYMENT_COMMAND",
                        "CHECK",
                        order,
                        attempt,
                        2,
                        clock.instant(),
                        6000,
                        Instant.parse("2030-01-01T00:06:00Z"),
                        false,
                        null);
        // 재요청의 금액·결제키 변경도 원본을 보존하며 각각 거절합니다.
        var original = command("APPROVE", 2);
        var changedAmount =
                new PaymentCommand(
                        UUID.randomUUID(),
                        1,
                        "PAYMENT_COMMAND",
                        "APPROVE",
                        order,
                        attempt,
                        2,
                        clock.instant(),
                        6001,
                        original.expiresAt(),
                        false,
                        key);
        var changedKey =
                new PaymentCommand(
                        UUID.randomUUID(),
                        1,
                        "PAYMENT_COMMAND",
                        "APPROVE",
                        order,
                        attempt,
                        2,
                        clock.instant(),
                        6000,
                        original.expiresAt(),
                        false,
                        "different-synthetic-payment-key");
        assertThatThrownBy(() -> receive(changedAmount))
                .isInstanceOf(com.msa.payment.domain.payment.InvalidPaymentCommandException.class);
        assertThatThrownBy(() -> receive(changedKey))
                .isInstanceOf(com.msa.payment.domain.payment.InvalidPaymentCommandException.class);
        assertThatThrownBy(() -> receive(changed))
                .isInstanceOf(com.msa.payment.domain.payment.InvalidPaymentCommandException.class);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                        "select expires_at from payment.payment_attempts where"
                                                + " id=?",
                                        java.sql.Timestamp.class,
                                        attempt)
                                .toInstant())
                .isEqualTo(Instant.parse("2030-01-01T00:05:00Z"));
    }

    KafkaConsumer<String, byte[]> consumer(String topic) {
        var consumer =
                new KafkaConsumer<String, byte[]>(
                        Map.of(
                                "bootstrap.servers",
                                KAFKA.getBootstrapServers(),
                                "group.id",
                                "payment-test-" + UUID.randomUUID(),
                                "auto.offset.reset",
                                "earliest"),
                        new StringDeserializer(),
                        new ByteArrayDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
