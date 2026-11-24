package com.msa.payment.domain.membership.client.impl;

import com.msa.payment.domain.membership.client.BillingClient;
import com.msa.payment.domain.membership.dto.request.BillingCharge;
import com.msa.payment.domain.membership.dto.response.BillingReply;
import com.msa.payment.domain.membership.dto.response.BillingReply.Status;
import com.msa.payment.global.config.membership.BillingProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 빌링 완료 웹훅 없이 승인 응답과 조회로 복구하며 원문 URI와 공급자 오류는 로그에 남기지 않습니다. */
@Component
@ConditionalOnProperty(name = "membership.billing.enabled", havingValue = "true")
public class BillingClientImpl implements BillingClient {
    // 공식 카드 자동결제 오류 중 미승인이 확정된 카드 거절만 업무 실패로 분류합니다.
    private static final Set<String> DECLINES =
            Set.of(
                    "INVALID_STOPPED_CARD",
                    "INVALID_REJECT_CARD",
                    "INVALID_CARD_EXPIRATION",
                    "INVALID_CARD_NUMBER",
                    "INVALID_CARD_LOST_OR_STOLEN",
                    "REJECT_CARD_PAYMENT",
                    "REJECT_CARD_COMPANY");
    private final HttpClient http;
    private final BillingProperties properties;
    private final JsonMapper json;

    public BillingClientImpl(
            @Qualifier("billingHttpClient") HttpClient http,
            BillingProperties properties,
            JsonMapper json) {
        this.http = http;
        this.properties = properties;
        this.json = json;
    }

    public BillingReply issue(String authKey, String customerKey, String idempotencyKey) {
        try {
            var response =
                    request(
                            "POST",
                            "/v1/billing/authorizations/issue",
                            Map.of("authKey", authKey, "customerKey", customerKey),
                            idempotencyKey);
            var body = body(response);
            if (response.statusCode() == 200
                    && body != null
                    && customerKey.equals(body.get("customerKey"))
                    && body.get("billingKey") instanceof String key
                    && validKey(key)) return new BillingReply(Status.ISSUED, key, null, null, null);
            return BillingReply.unknown("BILLING_ISSUE_UNKNOWN");
        } catch (Exception failure) {
            interrupted(failure);
            return BillingReply.unknown("BILLING_ISSUE_UNKNOWN");
        }
    }

    public BillingReply charge(String billingKey, BillingCharge charge) {
        try {
            var response =
                    request(
                            "POST",
                            "/v1/billing/" + path(billingKey),
                            Map.of(
                                    "customerKey",
                                    charge.customerKey(),
                                    "amount",
                                    charge.amount(),
                                    "orderId",
                                    charge.orderId(),
                                    "orderName",
                                    "STOCKDROP 멤버십"),
                            charge.idempotencyKey());
            var parsed = payment(response, charge, null);
            if (parsed.status() == Status.APPROVED) return parsed;
            var body = body(response);
            if ((response.statusCode() == 400 || response.statusCode() == 403)
                    && body != null
                    && body.get("code") instanceof String code
                    && DECLINES.contains(code))
                return new BillingReply(Status.DECLINED, null, null, null, code);
            // 중복 orderId, 인증 오류, 5xx 등은 새 멱등 키를 발급할 근거가 아닙니다.
            return BillingReply.unknown("BILLING_APPROVAL_UNKNOWN");
        } catch (Exception failure) {
            interrupted(failure);
            return BillingReply.unknown("BILLING_APPROVAL_UNKNOWN");
        }
    }

    public BillingReply lookup(BillingCharge charge, String paymentKey) {
        try {
            String route =
                    paymentKey == null
                            ? "/v1/payments/orders/" + path(charge.orderId())
                            : "/v1/payments/" + path(paymentKey);
            return payment(request("GET", route, null, null), charge, paymentKey);
        } catch (Exception failure) {
            interrupted(failure);
            return BillingReply.unknown("BILLING_LOOKUP_UNKNOWN");
        }
    }

    public BillingReply delete(String billingKey) {
        try {
            var response = request("DELETE", "/v1/billing/" + path(billingKey), null, null);
            if (response.statusCode() == 200 && response.body().length == 0)
                return new BillingReply(Status.DELETED, null, null, null, null);
            return BillingReply.unknown("BILLING_DELETE_UNKNOWN");
        } catch (Exception failure) {
            interrupted(failure);
            return BillingReply.unknown("BILLING_DELETE_UNKNOWN");
        }
    }

    private BillingReply payment(
            HttpResponse<byte[]> response, BillingCharge charge, String expectedKey) {
        var body = body(response);
        if (response.statusCode() != 200 || body == null)
            return BillingReply.unknown("BILLING_LOOKUP_UNKNOWN");
        try {
            if (!charge.orderId().equals(body.get("orderId"))
                    || !"BILLING".equals(body.get("type"))
                    || !"KRW".equals(body.get("currency"))
                    || !"DONE".equals(body.get("status"))
                    || number(body.get("totalAmount")) != charge.amount()
                    || number(body.get("balanceAmount")) != charge.amount()
                    || !(body.get("paymentKey") instanceof String key)
                    || !validKey(key)
                    || key.length() > 200
                    || expectedKey != null && !expectedKey.equals(key))
                return BillingReply.unknown("BILLING_CONTRACT_INVALID");
            var approvedAt = OffsetDateTime.parse((String) body.get("approvedAt")).toInstant();
            return new BillingReply(Status.APPROVED, null, key, approvedAt, null);
        } catch (RuntimeException invalid) {
            return BillingReply.unknown("BILLING_CONTRACT_INVALID");
        }
    }

    private HttpResponse<byte[]> request(
            String method, String route, Object payload, String idempotency) throws Exception {
        if (!properties.enabled()) throw new IllegalStateException();
        var request =
                HttpRequest.newBuilder(properties.baseUrl().resolve(route))
                        .timeout(properties.requestTimeout())
                        .header("Content-Type", "application/json")
                        .header(
                                "Authorization",
                                "Basic "
                                        + Base64.getEncoder()
                                                .encodeToString(
                                                        (properties.secretKey() + ":")
                                                                .getBytes(StandardCharsets.UTF_8)));
        if ("POST".equals(method)) {
            if (idempotency == null || idempotency.isBlank() || idempotency.length() > 300)
                throw new IllegalArgumentException();
            request.header("Idempotency-Key", idempotency)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)));
        } else if ("DELETE".equals(method)) request.DELETE();
        else request.GET();
        var exchange = http.sendAsync(request.build(), info -> new LimitedBodySubscriber());
        try {
            return exchange.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException failure) {
            // 본문 수신까지 제한하고 원본 교환을 취소해 임대시간 안에 복구합니다.
            exchange.cancel(true);
            throw failure;
        }
    }

    /** 수신 중 상한을 검사해 공급자 본문이 힙을 무제한 사용하지 못하게 합니다. */
    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result =
                new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;

        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 65536 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new java.io.IOException("BILLING_RESPONSE_TOO_LARGE"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }

    private Map<?, ?> body(HttpResponse<byte[]> response) {
        if (response.body() == null || response.body().length > 65536) return null;
        try {
            return json.readValue(response.body(), Map.class);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private long number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException();
        return new java.math.BigDecimal(number.toString()).longValueExact();
    }

    private boolean validKey(String value) {
        return value != null && !value.isBlank() && value.length() <= 1000;
    }

    private String path(String value) {
        if (!validKey(value)) throw new IllegalArgumentException();
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void interrupted(Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
    }
}
