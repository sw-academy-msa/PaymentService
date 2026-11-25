package com.msa.payment.domain.analytics.client.impl;

import com.msa.payment.domain.analytics.client.*;
import com.msa.payment.domain.analytics.dto.request.PaymentAnalytics;
import com.msa.payment.domain.analytics.dto.response.GaWork;
import com.msa.payment.global.config.analytics.GaProperties;
import com.msa.payment.global.config.client.ClientProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 전송 직전 가명 ID와 동의를 확인하며 개인정보와 비밀 URL을 로그에 남기지 않습니다. */
@Component
public class GaClientImpl implements GaClient {
    private final InternalHttp internal;
    private final ServiceTokenProvider tokens;
    private final ClientProperties clients;
    private final HttpClient http;
    private final GaProperties properties;
    private final JsonMapper json;

    public GaClientImpl(
            InternalHttp internal,
            ServiceTokenProvider tokens,
            ClientProperties clients,
            @Qualifier("gaHttpClient") HttpClient http,
            GaProperties properties,
            JsonMapper json) {
        this.internal = internal;
        this.tokens = tokens;
        this.clients = clients;
        this.http = http;
        this.properties = properties;
        this.json = json;
    }

    public String send(GaWork work) {
        if (!properties.enabled()) return "SKIPPED";
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("분석 HTTP는 트랜잭션 밖에서 실행해야 합니다.");
        var a = json.readValue(work.payload(), PaymentAnalytics.class);
        var reply =
                internal.send(
                        clients.ai(),
                        "GET",
                        "/internal/v1/personalization/eligibility?accountId="
                                + a.accountId()
                                + "&analyticsId="
                                + a.analyticsId(),
                        Map.of("Authorization", "Bearer " + tokens.token()),
                        null);
        if (reply.status() == 401) tokens.invalidate();
        if (reply.status() != 200) throw new RemoteUnavailable();
        var envelope = json.readTree(reply.body());
        if (!envelope.path("success").isBoolean() || !envelope.path("success").booleanValue())
            throw new RemoteUnavailable();
        // AI 내부 응답 AnalyticsEligibilityResponse의 필드 이름입니다.
        var eligible = envelope.path("data").path("analyticsEnabled");
        if (!eligible.isBoolean()) throw new RemoteUnavailable();
        if (!eligible.booleanValue()) return "SKIPPED";
        try {
            var uri =
                    URI.create(
                            properties.baseUrl().toString().replaceAll("/$", "")
                                    + "/mp/collect?measurement_id="
                                    + encode(properties.measurementId())
                                    + "&api_secret="
                                    + encode(properties.apiSecret()));
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(body(work, a))))
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            // MP의 2xx는 수신 확인일 뿐 본문 검증 성공을 의미하지 않습니다.
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? "SENT"
                    : response.statusCode() == 429 || response.statusCode() >= 500
                            ? "RETRY"
                            : "FAILED";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RemoteUnavailable();
        } catch (java.io.IOException failure) {
            throw new RemoteUnavailable();
        }
    }

    public Map<String, Object> body(GaWork w, PaymentAnalytics a) {
        var item = new LinkedHashMap<String, Object>();
        item.put("item_id", a.productId());
        item.put("item_name", a.productName());
        item.put("price", a.unitPrice());
        item.put("quantity", a.quantity());
        item.put(
                "discount",
                BigDecimal.valueOf(a.couponDiscount())
                        .divide(BigDecimal.valueOf(a.quantity()), 6, RoundingMode.DOWN));
        var params = new LinkedHashMap<String, Object>();
        params.put("transaction_id", w.orderId());
        params.put("currency", "KRW");
        params.put("value", w.amount());
        params.put("shipping", a.shipping());
        if (a.couponName() != null) params.put("coupon", a.couponName());
        params.put("items", List.of(item));
        params.put("session_id", new BigInteger(a.sessionId()));
        params.put("engagement_time_msec", 1);
        return Map.of(
                "client_id",
                a.clientId(),
                "user_id",
                a.analyticsId(),
                "timestamp_micros",
                Math.multiplyExact(w.occurredAt().getEpochSecond(), 1000000L),
                "events",
                List.of(
                        Map.of(
                                "name",
                                w.kind().equals("PURCHASE") ? "purchase" : "refund",
                                "params",
                                params)));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
