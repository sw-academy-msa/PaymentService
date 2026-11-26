package com.msa.payment.domain.payment.client.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.payment.domain.payment.dto.response.TossPaymentRequest;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.global.config.toss.TossProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;

/** 로컬 공급자 모의로 광고 승인과 취소가 구매 주문 계약과 섞이지 않는지 검증합니다. */
class AdvertisingTossClientTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID ID = UUID.fromString("e3a913bb-fae6-4bd6-937e-283e57f38b1d");
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final List<String> calls = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final List<Map<?, ?>> bodies = new ArrayList<>();
    private HttpServer server;
    private TossClientImpl client;
    private int responseStatus = 200;
    private String lookupState = "DONE";
    private String cancellationState = "CANCELED";
    private long cancellationBalance;
    private String providerOrderId = "AD-" + ID;
    private String providerPaymentKey = "synthetic-ad-key";
    private long providerAmount = 200000;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    calls.add(exchange.getRequestMethod() + " " + path);
                    keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    byte[] request = exchange.getRequestBody().readAllBytes();
                    bodies.add(request.length == 0 ? Map.of() : json.readValue(request, Map.class));
                    boolean cancel = path.endsWith("/cancel");
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("orderId", providerOrderId);
                    response.put("paymentKey", providerPaymentKey);
                    response.put("currency", "KRW");
                    response.put("totalAmount", providerAmount);
                    response.put("balanceAmount", cancel ? cancellationBalance : providerAmount);
                    response.put("status", cancel ? cancellationState : lookupState);
                    response.put("approvedAt", "2030-01-01T09:00:00+09:00");
                    byte[] bytes = json.writeValueAsBytes(response);
                    exchange.sendResponseHeaders(responseStatus, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        client =
                new TossClientImpl(
                        HttpClient.newHttpClient(),
                        new TossProperties(
                                true,
                                "test_gsk_synthetic",
                                "test_gck_synthetic",
                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                Duration.ofSeconds(2),
                                true),
                        json,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TossPaymentRequest work(boolean first, boolean cancel, Instant expiry) {
        return TossPaymentRequest.advertising(
                ID, 200000, expiry, "synthetic-ad-key", first, true, cancel);
    }

    @Test
    void approvalUsesOriginalAmountAdvertisingOrderAndStableIdempotencyKey() {
        var request = work(true, false, NOW.plusSeconds(60));
        assertThat(client.reconcile(request).status()).isEqualTo(Status.APPROVED);
        assertThat(client.reconcile(request).status()).isEqualTo(Status.APPROVED);
        assertThat(calls).containsOnly("POST /v1/payments/confirm");
        assertThat(keys).containsOnly("stockdrop-ad-approve-" + ID);
        assertThat(bodies.getFirst())
                .isEqualTo(
                        Map.of(
                                "orderId",
                                "AD-" + ID,
                                "amount",
                                200000,
                                "paymentKey",
                                "synthetic-ad-key"));
        assertThat(bodies.get(1)).isEqualTo(bodies.getFirst());
    }

    @Test
    void cancellationLooksUpFirstAndConfirmsOnlyZeroBalance() {
        var request = work(true, true, NOW.plusSeconds(60));
        assertThat(client.reconcile(request).status()).isEqualTo(Status.CANCELED);
        assertThat(calls)
                .containsExactly(
                        "GET /v1/payments/synthetic-ad-key",
                        "POST /v1/payments/synthetic-ad-key/cancel");
        assertThat(keys.getLast()).isEqualTo("stockdrop-ad-cancel-" + ID);
        assertThat(bodies.getLast()).isEqualTo(Map.of("cancelReason", "상품 판매 심사 반려"));
        cancellationBalance = 1;
        assertThat(client.reconcile(request).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void lateApprovalIsDiscoveredAfterExpiryWithoutNewConfirmation() {
        assertThat(client.reconcile(work(false, false, NOW.minusSeconds(1))).status())
                .isEqualTo(Status.APPROVED);
        assertThat(calls).containsExactly("GET /v1/payments/synthetic-ad-key");
    }

    @Test
    void mismatchedOrderAmountAndPaymentKeyNeverBecomePaid() {
        providerOrderId = "SD-" + ID;
        assertThat(client.reconcile(work(true, false, NOW.plusSeconds(60))).status())
                .isEqualTo(Status.UNKNOWN);
        providerOrderId = "AD-" + ID;
        providerAmount = 200001;
        assertThat(client.reconcile(work(true, false, NOW.plusSeconds(60))).status())
                .isEqualTo(Status.UNKNOWN);
        providerAmount = 200000;
        providerPaymentKey = "another-synthetic-key";
        assertThat(client.reconcile(work(true, false, NOW.plusSeconds(60))).status())
                .isEqualTo(Status.UNKNOWN);
    }

    @Test
    void providerFailuresAndPartialCancellationRemainUnknown() {
        var request = work(false, true, NOW.minusSeconds(1));
        cancellationState = "PARTIAL_CANCELED";
        assertThat(client.reconcile(request).status()).isEqualTo(Status.UNKNOWN);
        for (int status : List.of(401, 409, 429, 500)) {
            responseStatus = status;
            assertThat(client.reconcile(request).status()).isEqualTo(Status.UNKNOWN);
        }
    }

    @Test
    void requestRedactsProviderKeyAndRejectsBelowMinimum() {
        assertThat(work(true, false, NOW).toString()).isEqualTo("TossPaymentRequest[REDACTED]");
        assertThatThrownBy(
                        () ->
                                TossPaymentRequest.advertising(
                                        ID, 999, NOW, "key", true, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
