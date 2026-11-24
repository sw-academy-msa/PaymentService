package com.msa.payment.domain.membership.client.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.payment.domain.membership.dto.request.BillingCharge;
import com.msa.payment.domain.membership.dto.response.BillingReply.Status;
import com.msa.payment.global.config.membership.BillingProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 기존 JDK 합성 서버 방식으로 멱등 전송과 승인 불명/거절/조회/발급/삭제를 검증합니다. */
class BillingClientImplTest {
    HttpServer server;
    BillingClientImpl client;
    volatile int status;
    volatile String body;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final BillingCharge charge =
            new BillingCharge(
                    "sdm-fixture-attempt",
                    4900,
                    "fixture-customer-0000000001",
                    "sdm-bill-fixture-attempt");

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    var input =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    calls.add(
                            exchange.getRequestMethod()
                                    + " "
                                    + exchange.getRequestURI().getPath()
                                    + " "
                                    + exchange.getRequestHeaders().getFirst("Idempotency-Key")
                                    + " "
                                    + input);
                    byte[] response = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            status, response.length == 0 ? -1 : response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        var props =
                new BillingProperties(
                        true,
                        "test_ck_fixture",
                        "test_sk_fixture",
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Duration.ofSeconds(65),
                        true);
        client =
                new BillingClientImpl(
                        HttpClient.newHttpClient(), props, JsonMapper.builder().build());
        status = 200;
        body = payment(4900);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String payment(long amount) {
        return "{\"orderId\":\""
                + charge.orderId()
                + "\",\"type\":\"BILLING\",\"currency\":\"KRW\","
                + "\"status\":\"DONE\",\"totalAmount\":"
                + amount
                + ",\"balanceAmount\":"
                + amount
                + ",\"paymentKey\":\"fixture-payment\",\"approvedAt\":\"2026-11-25T09:00:00+09:00\"}";
    }

    @Test
    void timesOutWhenHeadersArriveButBodyStalls() throws Exception {
        var release = new CountDownLatch(1);
        server.removeContext("/");
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        var props =
                new BillingProperties(
                        true,
                        "test_ck_fixture",
                        "test_sk_fixture",
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Duration.ofMillis(250),
                        true);
        client =
                new BillingClientImpl(
                        HttpClient.newHttpClient(), props, JsonMapper.builder().build());
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () ->
                            assertThat(client.charge("fixture-billing", charge).status())
                                    .isEqualTo(Status.UNKNOWN));
        } finally {
            release.countDown();
        }
    }

    @Test
    void repeatsIdenticalRequestAndValidatesApprovalAmount() {
        assertThat(client.charge("fixture-billing", charge).status()).isEqualTo(Status.APPROVED);
        assertThat(client.charge("fixture-billing", charge).status()).isEqualTo(Status.APPROVED);
        assertThat(calls.get(0)).isEqualTo(calls.get(1)).contains("sdm-bill-fixture-attempt");
        body = payment(4901);
        assertThat(client.charge("fixture-billing", charge).status()).isEqualTo(Status.UNKNOWN);
        assertThat(charge.toString()).doesNotContain(charge.customerKey());
    }

    @Test
    void onlyConfirmedCardRejectionAllowsAnotherBusinessAttempt() {
        status = 403;
        body = "{\"code\":\"REJECT_CARD_PAYMENT\"}";
        assertThat(client.charge("fixture-billing", charge).status()).isEqualTo(Status.DECLINED);
        for (int code : new int[] {400, 401, 409, 429, 500}) {
            status = code;
            body = "{\"code\":\"DUPLICATED_ORDER_ID\"}";
            assertThat(client.charge("fixture-billing", charge).status()).isEqualTo(Status.UNKNOWN);
        }
    }

    @Test
    void looksUpByOrderWithoutAssumingMissingPaymentIsDeclined() {
        assertThat(client.lookup(charge, null).status()).isEqualTo(Status.APPROVED);
        assertThat(calls.getFirst()).startsWith("GET /v1/payments/orders/" + charge.orderId());
        assertThat(client.lookup(charge, "another-payment").status()).isEqualTo(Status.UNKNOWN);
        status = 404;
        body = "{}";
        assertThat(client.lookup(charge, null).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void bindsIssuedKeyToCustomerAndRequiresSuccessfulDelete() {
        body =
                "{\"billingKey\":\"fixture-issued\",\"customerKey\":\""
                        + charge.customerKey()
                        + "\"}";
        var result = client.issue("fixture-auth", charge.customerKey(), "fixture-session-key");
        assertThat(result.status()).isEqualTo(Status.ISSUED);
        assertThat(result.toString()).doesNotContain("fixture-issued");
        assertThat(client.issue("fixture-auth", "different-customer", "fixture-other-key").status())
                .isEqualTo(Status.UNKNOWN);
        body = "";
        assertThat(client.delete("fixture-issued").status()).isEqualTo(Status.DELETED);
        status = 500;
        assertThat(client.delete("fixture-issued").status()).isEqualTo(Status.UNKNOWN);
    }
}
