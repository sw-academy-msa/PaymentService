package com.msa.payment.domain.advertising.service.impl;

import com.msa.payment.domain.advertising.dto.request.AdvertisingCommand;
import com.msa.payment.domain.advertising.entity.*;
import com.msa.payment.domain.advertising.exception.*;
import com.msa.payment.domain.advertising.repository.*;
import com.msa.payment.domain.advertising.service.*;
import com.msa.payment.global.config.advertising.AdvertisingProperties;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** 신청별 잠금과 영수증으로 중복 및 취소 선도착을 공급자 호출 없이 처리합니다. */
@Service
public class AdvertisingCommandServiceImpl implements AdvertisingCommandService {
    private final AdvertisingPaymentRepository payments;
    private final AdvertisingCommandReceiptRepository receipts;
    private final AdvertisingResultOutboxService results;
    private final EntityManager em;
    private final JsonMapper json;
    private final Validator validator;
    private final Clock clock;
    private final AdvertisingProperties policy;

    public AdvertisingCommandServiceImpl(
            AdvertisingPaymentRepository payments,
            AdvertisingCommandReceiptRepository receipts,
            AdvertisingResultOutboxService results,
            EntityManager em,
            JsonMapper json,
            Validator validator,
            Clock clock,
            AdvertisingProperties policy) {
        this.payments = payments;
        this.receipts = receipts;
        this.results = results;
        this.em = em;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
        this.policy = policy;
    }

    @Transactional(timeout = 5)
    public void receive(String key, byte[] bytes) {
        var c = parse(key, bytes);
        var p = c.payload();
        String hash = hash(json.writeValueAsBytes(c));
        new TreeSet<>(
                        List.of(
                                "account:" + p.sellerId(),
                                "event:" + c.eventId(),
                                "application:" + p.applicationId()))
                .forEach(payments::lockIdentity);
        var receipt = receipts.findById(c.eventId()).orElse(null);
        if (receipt != null) {
            if (!receipt.payloadHash.equals(hash)) invalid();
            return;
        }
        if (receipts.existsByApplicationIdAndAggregateVersion(
                p.applicationId(), c.aggregateVersion())) invalid();
        var payment = payments.findByApplicationId(p.applicationId()).orElse(null);
        if (payment != null) {
            payment = payments.lock(payment.id).orElseThrow();
            if (!payment.submissionId.equals(p.submissionId())
                    || !payment.productId.equals(p.productId())
                    || !payment.sellerId.equals(p.sellerId())
                    || p.paymentId() != null && !payment.id.equals(p.paymentId())) invalid();
        }
        if ("AdvertisingPaymentRequested".equals(c.eventType())) {
            if (payment == null) {
                payment =
                        AdvertisingPayment.prepare(
                                p.applicationId(),
                                p.submissionId(),
                                p.productId(),
                                p.sellerId(),
                                p.amount(),
                                p.quoteHash(),
                                p.expiresAt(),
                                clock.instant());
                var tombstones =
                        receipts.findByApplicationIdAndEventType(
                                p.applicationId(), "AdvertisingPaymentCancellationRequested");
                for (var tombstone : tombstones) {
                    if (!tombstone.submissionId.equals(p.submissionId())
                            || !tombstone.productId.equals(p.productId())
                            || !tombstone.sellerId.equals(p.sellerId())) invalid();
                }
                payment.cancellationRequested = !tombstones.isEmpty();
                boolean withdrawing =
                        em.createQuery(
                                                "select count(f) from MembershipWithdrawalFence f"
                                                        + " where f.accountId=:account and"
                                                        + " f.canceled=false",
                                                Long.class)
                                        .setParameter("account", p.sellerId())
                                        .getSingleResult()
                                > 0;
                if (payment.cancellationRequested) {
                    payment.status = "FAILED";
                    payment.providerState = "NOT_STARTED";
                    payment.lastFailureCode = "CANCELED_BEFORE_PREPARATION";
                } else if (withdrawing) {
                    payment.status = "FAILED";
                    payment.providerState = "NOT_STARTED";
                    payment.lastFailureCode = "ACCOUNT_WITHDRAWAL_BLOCKED";
                } else if (!policy.enabled()) {
                    // 신규 접수만 거절하며 이미 생성된 원본과 취소 명령의 처리는 계속합니다.
                    payment.status = "FAILED";
                    payment.providerState = "NOT_STARTED";
                    payment.lastFailureCode = "ADMISSION_DISABLED";
                }
                em.persist(payment);
                results.record(payment, "AdvertisingPaymentPrepared", c.eventId());
            } else if (payment.amount != p.amount()
                    || !payment.quoteHash.equals(p.quoteHash())
                    || !payment.expiresAt.equals(p.expiresAt())) invalid();
        } else if (payment != null && !payment.cancellationRequested) {
            payment.cancellationRequested = true;
            payment.cancellationReasonCode = p.reasonCode();
            payment.nextAttemptAt = clock.instant();
            results.record(payment, "AdvertisingPaymentStateChanged", c.eventId());
        }
        em.persist(
                new AdvertisingCommandReceipt(
                        c.eventId(),
                        p.applicationId(),
                        p.submissionId(),
                        p.productId(),
                        p.sellerId(),
                        hash,
                        c.eventType(),
                        c.aggregateVersion(),
                        clock.instant()));
    }

    private AdvertisingCommand parse(String key, byte[] bytes) {
        try {
            if (bytes == null || bytes.length > 16384) invalid();
            var c =
                    json.readerFor(AdvertisingCommand.class)
                            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                            .<AdvertisingCommand>readValue(bytes);
            if (c == null
                    || !validator.validate(c).isEmpty()
                    || !c.aggregateId().equals(c.payload().applicationId())
                    || !c.aggregateId().toString().equals(key)) invalid();
            var p = c.payload();
            if ("AdvertisingPaymentRequested".equals(c.eventType())) {
                if (p.amount() == null
                        || p.amount() < 1000
                        || !"KRW".equals(p.currency())
                        || !"V1".equals(p.formulaVersion())
                        || p.expiresAt() == null
                        || p.quoteHash() == null
                        || !p.quoteHash().matches("[a-f0-9]{64}")
                        || p.productVersion() == null
                        || p.productVersion() < 0) invalid();
            } else if ("AdvertisingPaymentCancellationRequested".equals(c.eventType())) {
                if (!Set.of("PRODUCT_REJECTED", "SUBMISSION_FAILED", "SELLER_ABANDONED")
                        .contains(p.reasonCode() == null ? "" : p.reasonCode())) invalid();
            } else invalid();
            return c;
        } catch (Exception failure) {
            throw new AdvertisingException(AdvertisingErrorCode.INVALID_COMMAND);
        }
    }

    private void invalid() {
        throw new AdvertisingException(AdvertisingErrorCode.INVALID_COMMAND);
    }

    private String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 미지원");
        }
    }
}
