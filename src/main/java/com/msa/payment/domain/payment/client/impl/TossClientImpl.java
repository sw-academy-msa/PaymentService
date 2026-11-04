package com.msa.payment.domain.payment.client.impl;

import com.msa.payment.domain.payment.client.TossClient;
import com.msa.payment.domain.payment.dto.response.*;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.global.config.toss.TossProperties;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 주문서형/결제창 키가 지원하는 paymentKey 조회를 사용합니다. 원문 오류·인증 헤더는 로그에 남기지 않습니다. */
@Component
public class TossClientImpl implements TossClient {
    private final HttpClient http;
    private final TossProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public TossClientImpl(HttpClient http, TossProperties props, JsonMapper json, Clock clock) {
        this.http = http;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    public ProviderReply reconcile(PaymentWork work) {
        if (!props.enabled()) return ProviderReply.unknown("TOSS_DISABLED");
        // CHECK가 APPROVE보다 먼저 와서 키가 없으면 가맹점 주문번호 조회로 우회하지 않습니다.
        if (work.paymentKey() == null) return ProviderReply.unknown("PAYMENT_KEY_NOT_RECEIVED");
        try {
            if (work.firstApproval() && canApprove(work)) return approved(work);
            var response = request("GET", "/v1/payments/" + path(work.paymentKey()), null, null);
            if (response.statusCode() == 404 && canApprove(work)) return approved(work);
            var body = validated(response, work);
            if (body == null) return ProviderReply.unknown("PROVIDER_RESULT_UNKNOWN");
            String status = (String) body.get("status");
            if (status.equals("IN_PROGRESS") && canApprove(work)) return approved(work);
            if (status.equals("DONE") && work.cancellationRequested()) {
                response =
                        request(
                                "POST",
                                "/v1/payments/" + path(work.paymentKey()) + "/cancel",
                                Map.of("cancelReason", "주문 처리 보상 취소"),
                                "stockdrop-cancel-" + work.attemptId());
                body = validated(response, work);
                if (body == null) return ProviderReply.unknown("CANCELLATION_RESULT_UNKNOWN");
            }
            return reply(body, work);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return ProviderReply.unknown("PROVIDER_RESULT_UNKNOWN");
        }
    }

    private boolean canApprove(PaymentWork work) {
        return work.approvalRequested()
                && !work.cancellationRequested()
                && clock.instant().isBefore(work.expiresAt());
    }

    private ProviderReply approved(PaymentWork work) throws Exception {
        // 재전송에서도 body와 멱등 키가 같으며 5분 이후 새 승인을 보내지 않습니다.
        if (!canApprove(work)) return ProviderReply.unknown("APPROVAL_DEADLINE_PASSED");
        var response =
                request(
                        "POST",
                        "/v1/payments/confirm",
                        Map.of(
                                "paymentKey",
                                work.paymentKey(),
                                "orderId",
                                work.tossOrderId(),
                                "amount",
                                work.amount()),
                        "stockdrop-approve-" + work.attemptId());
        var body = validated(response, work);
        return body == null ? ProviderReply.unknown("APPROVAL_RESULT_UNKNOWN") : reply(body, work);
    }

    private HttpResponse<byte[]> request(
            String method, String path, Object body, String idempotency) throws Exception {
        String credential =
                Base64.getEncoder()
                        .encodeToString((props.secretKey() + ":").getBytes(StandardCharsets.UTF_8));
        var builder =
                HttpRequest.newBuilder(props.baseUrl().resolve(path))
                        .timeout(props.requestTimeout())
                        .header("Authorization", "Basic " + credential)
                        .header("Content-Type", "application/json");
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        if (method.equals("GET")) builder.GET();
        else builder.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private Map<?, ?> validated(HttpResponse<byte[]> response, PaymentWork work) {
        try {
            if (response.statusCode() != 200 || response.body().length > 2097152) return null;
            Map<?, ?> body = json.readValue(response.body(), Map.class);
            if (!work.tossOrderId().equals(body.get("orderId"))
                    || !work.paymentKey().equals(body.get("paymentKey"))
                    || !"KRW".equals(body.get("currency"))
                    || number(body.get("totalAmount")) != work.amount()
                    || !(body.get("status") instanceof String)) return null;
            return body;
        } catch (Exception failure) {
            return null;
        }
    }

    private ProviderReply reply(Map<?, ?> body, PaymentWork work) {
        String status = (String) body.get("status");
        Instant approvedAt =
                body.get("approvedAt") == null
                        ? null
                        : OffsetDateTime.parse((String) body.get("approvedAt"))
                                .toInstant()
                                .truncatedTo(ChronoUnit.MILLIS);
        return switch (status) {
            case "DONE" ->
                    approvedAt != null && number(body.get("balanceAmount")) == work.amount()
                            ? ProviderReply.builder()
                                    .status(Status.APPROVED)
                                    .approvedAt(approvedAt)
                                    .paymentKey(work.paymentKey())
                                    .failureCode(null)
                                    .build()
                            : ProviderReply.unknown("PROVIDER_CONTRACT_INVALID");
            case "CANCELED" ->
                    number(body.get("balanceAmount")) == 0
                            ? ProviderReply.builder()
                                    .status(Status.CANCELED)
                                    .approvedAt(approvedAt)
                                    .paymentKey(work.paymentKey())
                                    .failureCode(null)
                                    .build()
                            : ProviderReply.unknown("PROVIDER_CONTRACT_INVALID");
            case "ABORTED", "EXPIRED" ->
                    ProviderReply.builder()
                            .status(Status.DECLINED)
                            .approvedAt(null)
                            .paymentKey(work.paymentKey())
                            .failureCode(null)
                            .build();
            // 404·부분취소·입금대기·진행 중은 실패 확정이나 임의 재고 반환의 근거가 아닙니다.
            default -> ProviderReply.unknown("PROVIDER_PENDING");
        };
    }

    private long number(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("공급자 금액 계약 오류");
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }

    private String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
