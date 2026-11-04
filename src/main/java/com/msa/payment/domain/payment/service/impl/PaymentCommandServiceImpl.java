package com.msa.payment.domain.payment.service.impl;

import com.msa.payment.domain.payment.InvalidPaymentCommandException;
import com.msa.payment.domain.payment.dto.request.PaymentCommand;
import com.msa.payment.domain.payment.entity.*;
import com.msa.payment.domain.payment.repository.*;
import com.msa.payment.domain.payment.service.*;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** 명령 순서가 뒤집혀도 취소 의도는 유지합니다. commandVersion을 최신 스냅샷처럼 건너뛰지 않습니다. */
@Service
public class PaymentCommandServiceImpl implements PaymentCommandService {
    private final EntityManager em;
    private final PaymentAttemptRepository attempts;
    private final PaymentCommandReceiptRepository receipts;
    private final JsonMapper json;
    private final Validator validator;
    private final Clock clock;
    private final PaymentResultOutboxService results;

    public PaymentCommandServiceImpl(
            EntityManager em,
            PaymentAttemptRepository attempts,
            PaymentCommandReceiptRepository receipts,
            JsonMapper json,
            Validator validator,
            Clock clock,
            PaymentResultOutboxService results) {
        this.em = em;
        this.attempts = attempts;
        this.receipts = receipts;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
        this.results = results;
    }

    @Transactional(timeout = 5)
    public void receive(String key, byte[] payload) {
        PaymentCommand c = parse(key, payload);
        String hash = hash(json.writeValueAsBytes(c));
        // 주문·시도·이벤트 ID의 교차 재사용도 직렬화합니다. 고정 순서로 잡아 교착을 피합니다.
        var locks =
                new TreeSet<>(
                        List.of(
                                "order:" + c.orderId(),
                                "attempt:" + c.paymentAttemptId(),
                                "event:" + c.eventId()));
        if (c.paymentKey() != null)
            locks.add(
                    "provider:"
                            + hash(
                                    c.paymentKey()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        locks.forEach(attempts::lockCommandIdentity);
        var receipt = receipts.findById(c.eventId()).orElse(null);
        if (receipt != null) {
            if (!receipt.payloadHash.equals(hash)) invalid();
            return;
        }
        if (receipts.existsByAttemptIdAndCommandVersion(c.paymentAttemptId(), c.commandVersion()))
            invalid();
        var previous = attempts.findIdByOrderId(c.orderId());
        if (previous.isPresent() && !previous.get().equals(c.paymentAttemptId())) invalid();
        var a = attempts.lock(c.paymentAttemptId()).orElse(null);
        if (a == null) {
            a =
                    PaymentAttempt.builder()
                            .id(c.paymentAttemptId())
                            .order(c.orderId())
                            .amount(c.amount())
                            .expiresAt(c.expiresAt())
                            .now(clock.instant())
                            .build();
            em.persist(a);
        }
        if (!a.orderId.equals(c.orderId())
                || a.amount != c.amount()
                || !a.expiresAt.equals(c.expiresAt())) invalid();
        if (c.paymentKey() != null) {
            if (a.paymentKey != null && !a.paymentKey.equals(c.paymentKey())) invalid();
            var bound = attempts.findIdByPaymentKey(c.paymentKey());
            if (bound.isPresent() && !bound.get().equals(a.id)) invalid();
            a.paymentKey = c.paymentKey();
        }
        a.approvalRequested |= c.action().equals("APPROVE");
        a.cancellationRequested |= c.cancellationRequested() || c.action().equals("CANCEL");
        // 외부 승인을 한 번도 시작하지 않은 취소는 뒤늦은 APPROVE를 영구 차단할 수 있습니다.
        if (a.cancellationRequested
                && !a.confirmationStarted
                && a.status != PaymentAttempt.Status.APPROVED
                && a.status != PaymentAttempt.Status.DECLINED)
            a.status = PaymentAttempt.Status.CANCELED;
        a.nextAttemptAt = clock.instant();
        em.persist(
                PaymentCommandReceipt.builder()
                        .id(c.eventId())
                        .attempt(a.id)
                        .version(c.commandVersion())
                        .hash(hash)
                        .now(clock.instant())
                        .build());
        results.record(a);
    }

    private PaymentCommand parse(String key, byte[] bytes) {
        try {
            if (bytes == null || bytes.length > 1048576) invalid();
            Map<?, ?> raw = json.readValue(bytes, Map.class);
            for (var field : List.of("schemaVersion", "commandVersion", "amount")) {
                var value = raw.get(field);
                if (!(value instanceof Integer
                        || value instanceof Long
                        || value instanceof java.math.BigInteger)) invalid();
            }
            if (!(raw.get("cancellationRequested") instanceof Boolean)) invalid();
            if (raw.get("paymentKey") != null && !(raw.get("paymentKey") instanceof String))
                invalid();
            PaymentCommand c =
                    json.readerFor(PaymentCommand.class)
                            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                            .readValue(bytes);
            if (c == null
                    || !validator.validate(c).isEmpty()
                    || !c.paymentAttemptId().toString().equals(key)) invalid();
            if (c.action().equals("APPROVE")
                    ? (c.paymentKey() == null || c.paymentKey().isBlank())
                    : c.paymentKey() != null) invalid();
            if (c.action().equals("CANCEL") && !c.cancellationRequested()) invalid();
            return c;
        } catch (Exception ex) {
            throw new InvalidPaymentCommandException();
        }
    }

    private String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 미지원");
        }
    }

    private void invalid() {
        throw new InvalidPaymentCommandException();
    }
}
