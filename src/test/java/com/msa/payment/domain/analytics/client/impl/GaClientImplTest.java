package com.msa.payment.domain.analytics.client.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.payment.domain.analytics.client.*;
import com.msa.payment.domain.analytics.dto.request.PaymentAnalytics;
import com.msa.payment.domain.analytics.dto.response.*;
import com.msa.payment.global.config.analytics.GaProperties;
import com.msa.payment.global.config.client.ClientProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 실제 GA 대신 루프백 HTTP에서 MP 본문과 동의 철회 경계를 검증합니다. */
class GaClientImplTest {
    @Test
    void consentDenialNeverSendsAndPurchaseAndRefundShareTransactionId() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicReference<String>();
        server.createContext(
                "/mp/collect",
                e -> {
                    received.set(
                            new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    e.sendResponseHeaders(204, -1);
                    e.close();
                });
        server.start();
        try {
            var json = JsonMapper.builder().findAndAddModules().build();
            var internal = mock(InternalHttp.class);
            var tokens = mock(ServiceTokenProvider.class);
            when(tokens.token()).thenReturn("fixture-token");
            var properties =
                    new GaProperties(
                            true,
                            "G-TEST",
                            "fixture-secret",
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            true);
            var clients = new ClientProperties("http", "account-service", "ai-service", null, null);
            var client =
                    new GaClientImpl(
                            internal,
                            tokens,
                            clients,
                            HttpClient.newHttpClient(),
                            properties,
                            json);
            var account = UUID.randomUUID();
            var identity = UUID.randomUUID();
            var analytics =
                    new PaymentAnalytics(
                            account,
                            identity,
                            "123.456",
                            "123",
                            UUID.randomUUID(),
                            "합성 상품",
                            1,
                            30000,
                            30000,
                            3000,
                            "coupon",
                            0);
            var work =
                    new GaWork(
                            UUID.randomUUID(),
                            "PURCHASE",
                            json.writeValueAsString(analytics),
                            27000,
                            UUID.randomUUID(),
                            Instant.now());
            when(internal.send(anyString(), anyString(), anyString(), anyMap(), isNull()))
                    .thenReturn(
                            new InternalReply(
                                    200, "{\"success\":true,\"data\":{\"eligible\":false}}"));
            assertThat(client.send(work)).isEqualTo("SKIPPED");
            assertThat(received.get()).isNull();
            when(internal.send(anyString(), anyString(), anyString(), anyMap(), isNull()))
                    .thenReturn(
                            new InternalReply(
                                    200, "{\"success\":true,\"data\":{\"eligible\":true}}"));
            assertThat(client.send(work)).isEqualTo("SENT");
            var body = json.readTree(received.get());
            assertThat(body.path("user_id").asText()).isEqualTo(identity.toString());
            assertThat(body.path("events").get(0).path("name").asText()).isEqualTo("purchase");
            assertThat(body.path("events").get(0).path("params").path("transaction_id").asText())
                    .isEqualTo(work.orderId().toString());
            assertThat(received.get())
                    .doesNotContain(account.toString(), "api_secret", "fixture-secret");
            var refund =
                    new GaWork(
                            work.orderId(),
                            "REFUND",
                            work.payload(),
                            27000,
                            UUID.randomUUID(),
                            Instant.now());
            assertThat(client.send(refund)).isEqualTo("SENT");
            assertThat(json.readTree(received.get()).path("events").get(0).path("name").asText())
                    .isEqualTo("refund");
            when(internal.send(anyString(), anyString(), anyString(), anyMap(), isNull()))
                    .thenReturn(new InternalReply(200, "{\"success\":true,\"data\":{}}"));
            assertThatThrownBy(() -> client.send(work)).isInstanceOf(RemoteUnavailable.class);
        } finally {
            server.stop(0);
        }
    }
}
