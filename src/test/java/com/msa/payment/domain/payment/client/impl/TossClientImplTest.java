package com.msa.payment.domain.payment.client.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.payment.domain.payment.dto.response.PaymentWork;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.global.config.toss.TossProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 합성 토스 서버로 확정 거절만 DECLINED가 되고 처리 여부가 불명확한 응답은 UNKNOWN으로 남는지 확인합니다. */
class TossClientImplTest {
    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    HttpServer server;
    volatile int confirmStatus;
    volatile String confirmBody;
    volatile int lookupStatus;
    volatile String lookupBody;
    final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    boolean confirm = e.getRequestURI().getPath().equals("/v1/payments/confirm");
                    calls.add(e.getRequestMethod() + " " + e.getRequestURI().getPath());
                    byte[] body =
                            (confirm ? confirmBody : lookupBody).getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(confirm ? confirmStatus : lookupStatus, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    TossClientImpl client() {
        var props =
                new TossProperties(
                        true,
                        "test_gsk_synthetic",
                        "test_gck_synthetic",
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Duration.ofSeconds(3),
                        true);
        return new TossClientImpl(
                HttpClient.newHttpClient(),
                props,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // 첫 승인 작업입니다. 기한 전이므로 승인 요청을 보냅니다.
    PaymentWork approval() {
        return work(NOW.plusSeconds(60), true);
    }

    // 승인 시작 이력이 있고 기한이 지난 재조회 작업입니다. 새 승인 요청을 보내면 안 됩니다.
    PaymentWork expiredLookup() {
        return work(NOW.minusSeconds(1), false);
    }

    PaymentWork work(Instant expiresAt, boolean first) {
        return PaymentWork.builder()
                .attemptId(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .amount(1000)
                .expiresAt(expiresAt)
                .paymentKey("tgen_synthetic_key")
                .leaseToken(UUID.randomUUID())
                .firstApproval(first)
                .approvalRequested(true)
                .cancellationRequested(false)
                .build();
    }

    void confirmReplies(int status, String body) {
        confirmStatus = status;
        confirmBody = body;
    }

    void lookupReplies(int status, String body) {
        lookupStatus = status;
        lookupBody = body;
    }

    @Test
    void explicitApprovalRejectionIsDeclined() {
        confirmReplies(403, "{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과\"}");
        var reply = client().reconcile(approval());
        assertThat(reply.status()).isEqualTo(Status.DECLINED);
        assertThat(reply.failureCode()).isEqualTo("TOSS_REJECT_CARD_PAYMENT");
        assertThat(reply.approvedAt()).isNull();
    }

    @Test
    void expiredPaymentSessionOnApprovalIsDeclined() {
        confirmReplies(404, "{\"code\":\"NOT_FOUND_PAYMENT_SESSION\",\"message\":\"만료\"}");
        var reply = client().reconcile(approval());
        assertThat(reply.status()).isEqualTo(Status.DECLINED);
        assertThat(reply.failureCode()).isEqualTo("TOSS_NOT_FOUND_PAYMENT_SESSION");
    }

    @Test
    void possiblyProcessedOrProviderFailureStaysUnknown() {
        for (String code :
                List.of(
                        "ALREADY_PROCESSED_PAYMENT",
                        "PROVIDER_ERROR",
                        "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING")) {
            confirmReplies(400, "{\"code\":\"" + code + "\"}");
            var reply = client().reconcile(approval());
            assertThat(reply.status()).as(code).isEqualTo(Status.UNKNOWN);
            assertThat(reply.failureCode()).as(code).isEqualTo("APPROVAL_RESULT_UNKNOWN");
        }
    }

    @Test
    void merchantAuthConflictRateLimitAndServerErrorsStayUnknown() {
        for (int status : List.of(401, 409, 429, 500)) {
            confirmReplies(status, "{\"code\":\"SOME_ERROR\"}");
            assertThat(client().reconcile(approval()).status())
                    .as("HTTP " + status)
                    .isEqualTo(Status.UNKNOWN);
        }
    }

    @Test
    void rejectionWithoutParsableCodeStaysUnknown() {
        for (String body : List.of("<html>bad gateway</html>", "{}", "{\"code\":\"bad code\"}")) {
            confirmReplies(400, body);
            assertThat(client().reconcile(approval()).status()).as(body).isEqualTo(Status.UNKNOWN);
        }
    }

    @Test
    void lookupNotFoundAfterDeadlineIsDeclinedWithoutNewApproval() {
        lookupReplies(404, "{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"없음\"}");
        var reply = client().reconcile(expiredLookup());
        assertThat(reply.status()).isEqualTo(Status.DECLINED);
        assertThat(reply.failureCode()).isEqualTo("TOSS_NOT_FOUND_PAYMENT");
        assertThat(calls).noneMatch(call -> call.endsWith("/v1/payments/confirm"));
    }

    @Test
    void lookupNotFoundWithOtherOrMissingCodeStaysUnknown() {
        for (String body : List.of("{\"code\":\"NOT_FOUND\"}", "not found")) {
            lookupReplies(404, body);
            assertThat(client().reconcile(expiredLookup()).status())
                    .as(body)
                    .isEqualTo(Status.UNKNOWN);
        }
    }
}
