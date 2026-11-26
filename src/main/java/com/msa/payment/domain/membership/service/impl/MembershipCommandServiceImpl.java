package com.msa.payment.domain.membership.service.impl;

import com.msa.payment.domain.membership.dto.request.MembershipCommand;
import com.msa.payment.domain.membership.entity.*;
import com.msa.payment.domain.membership.exception.InvalidMembershipCommandException;
import com.msa.payment.domain.membership.repository.*;
import com.msa.payment.domain.membership.service.*;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** 금융 실행 시작과 같은 구독 행 잠금으로 해지와 해지 취소의 선후 관계를 결정합니다. */
@Service
public class MembershipCommandServiceImpl implements MembershipCommandService {
    private final MembershipSubscriptionRepository subscriptions;
    private final MembershipCommandReceiptRepository receipts;
    private final MembershipBillingCredentialRepository credentials;
    private final MembershipAuthSessionRepository sessions;
    private final MembershipBillingAttemptRepository attempts;
    private final MembershipWithdrawalFenceRepository fences;
    private final MembershipResultOutboxService results;
    private final EntityManager em;
    private final JsonMapper json;
    private final Validator validator;
    private final Clock clock;

    public MembershipCommandServiceImpl(
            MembershipSubscriptionRepository subscriptions,
            MembershipCommandReceiptRepository receipts,
            MembershipBillingCredentialRepository credentials,
            MembershipAuthSessionRepository sessions,
            MembershipBillingAttemptRepository attempts,
            MembershipWithdrawalFenceRepository fences,
            MembershipResultOutboxService results,
            EntityManager em,
            JsonMapper json,
            Validator validator,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.receipts = receipts;
        this.credentials = credentials;
        this.sessions = sessions;
        this.attempts = attempts;
        this.fences = fences;
        this.results = results;
        this.em = em;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional(timeout = 5)
    public void receive(String key, byte[] bytes) {
        var command = parse(key, bytes);
        String hash = hash(json.writeValueAsBytes(command));
        new TreeSet<>(List.of("account:" + command.accountId(), "event:" + command.eventId()))
                .forEach(subscriptions::lockIdentity);
        var receipt = receipts.findById(command.eventId()).orElse(null);
        if (receipt != null) {
            if (!receipt.payloadHash.equals(hash)) invalid();
            return;
        }
        if (receipts.existsByAccountIdAndCommandVersion(
                command.accountId(), command.commandVersion())) invalid();
        var s =
                subscriptions
                        .lock(command.subscriptionId())
                        .orElseThrow(InvalidMembershipCommandException::new);
        if (!s.accountId.equals(command.accountId()) || command.generation() > s.generation)
            invalid();
        // 늦게 도착한 이전 명령은 이후 명령의 결정을 되돌리지 않도록 수신 기록만 남깁니다.
        boolean stale =
                receipts.existsByAccountIdAndCommandVersionGreaterThan(
                        command.accountId(), command.commandVersion());
        if (!stale && "MEMBERSHIP_CANCEL_WITHDRAW_REQUESTED".equals(command.type()))
            withdraw(s, command);
        else if (!stale && command.generation() == s.generation) cancel(s);
        em.persist(
                MembershipCommandReceipt.builder()
                        .eventId(command.eventId())
                        .accountId(command.accountId())
                        .commandVersion(command.commandVersion())
                        .payloadHash(hash)
                        .processedAt(clock.instant())
                        .build());
    }

    private void cancel(MembershipSubscription s) {
        s.autoRenew = false;
        s.canceledAt = clock.instant();
        s.nextChargeAt = null;
        if (Set.of("GRACE", "PENDING", "NONE", "EXPIRED").contains(s.state)) {
            s.state = "EXPIRED";
            s.entitlementUntil = clock.instant();
        } else s.state = "CANCEL_SCHEDULED";
        // 실행하지 않은 미래 시도는 승인 시작 기록을 만들지 않고 종료합니다.
        var pending =
                em.createQuery(
                                "select a from MembershipBillingAttempt a,"
                                    + " MembershipBillingCycle c where a.cycleId=c.id and"
                                    + " c.subscriptionId=:id and a.firstRequestedAt is null and"
                                    + " a.status='PENDING'",
                                MembershipBillingAttempt.class)
                        .setParameter("id", s.id)
                        .getResultList();
        pending.forEach(a -> a.status = "CANCELED");
        var open =
                em.createQuery(
                                "select a from MembershipAuthSession a where"
                                    + " a.subscriptionId=:id and a.attempts=0 and a.state in"
                                    + " ('OPEN','READY')",
                                MembershipAuthSession.class)
                        .setParameter("id", s.id)
                        .getResultList();
        open.forEach(
                a -> {
                    a.state = "CANCELED";
                    a.authKeyCipher = null;
                });
        var credential = credentials.lock(s.id).orElse(null);
        // 해지 예약은 혜택 종료 전에 철회할 수 있도록 빌링키를 만료 시점에 삭제합니다.
        if ("EXPIRED".equals(s.state)
                && credential != null
                && "ACTIVE".equals(credential.deleteState)) {
            credential.deleteState = "PENDING";
            credential.deleteNextAt = clock.instant();
        }
        results.record(s, "MEMBERSHIP_CANCELED", 0);
    }

    private void withdraw(MembershipSubscription s, MembershipCommand command) {
        var now = clock.instant();
        var credential = credentials.lock(s.id).orElse(null);
        // 혜택 종료 전 해지 예약이고 빌링키가 공급자에 남아 있을 때만 자동 결제를 되살립니다.
        boolean allowed =
                command.generation() == s.generation
                        && "CANCEL_SCHEDULED".equals(s.state)
                        && !s.autoRenew
                        && s.periodEnd != null
                        && s.entitlementUntil != null
                        && now.isBefore(s.entitlementUntil)
                        && !fences.existsByAccountIdAndCanceledFalse(s.accountId)
                        && !attempts.hasUnresolved(s.id)
                        && credential != null
                        && "ACTIVE".equals(credential.deleteState)
                        && credential.billingKeyCipher != null;
        if (!allowed) {
            // 거절도 완전 스냅샷으로 발행해 Account가 처리 중 표시를 끝낼 수 있게 합니다.
            results.record(s, "MEMBERSHIP_CANCEL_WITHDRAW_REJECTED", 0);
            return;
        }
        s.autoRenew = true;
        s.state = "ACTIVE";
        s.canceledAt = null;
        // 현재 납부기간 종료 시각인 원래 결제일에 다음 자동 결제를 다시 예약합니다.
        s.nextChargeAt = s.periodEnd;
        results.record(s, "MEMBERSHIP_CANCEL_WITHDRAWN", 0);
    }

    private MembershipCommand parse(String key, byte[] bytes) {
        try {
            if (bytes == null || bytes.length > 16384) invalid();
            Map<?, ?> raw = json.readValue(bytes, Map.class);
            for (String field : List.of("schemaVersion", "generation", "commandVersion")) {
                if (!(raw.get(field) instanceof Integer || raw.get(field) instanceof Long))
                    invalid();
            }
            var c =
                    json.readerFor(MembershipCommand.class)
                            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                            .<MembershipCommand>readValue(bytes);
            if (c == null
                    || !validator.validate(c).isEmpty()
                    || !c.accountId().toString().equals(key)
                    || !Set.of(
                                    "MEMBERSHIP_CANCEL_REQUESTED",
                                    "MEMBERSHIP_CANCEL_WITHDRAW_REQUESTED")
                            .contains(c.type())) invalid();
            return c;
        } catch (Exception failure) {
            throw new InvalidMembershipCommandException();
        }
    }

    private void invalid() {
        throw new InvalidMembershipCommandException();
    }

    private String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 미지원");
        }
    }
}
