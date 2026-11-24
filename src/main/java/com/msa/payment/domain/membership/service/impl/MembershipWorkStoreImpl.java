package com.msa.payment.domain.membership.service.impl;

import com.msa.payment.domain.membership.dto.request.BillingCharge;
import com.msa.payment.domain.membership.dto.response.*;
import com.msa.payment.domain.membership.dto.response.BillingReply.Status;
import com.msa.payment.domain.membership.entity.*;
import com.msa.payment.domain.membership.repository.*;
import com.msa.payment.domain.membership.service.*;
import com.msa.payment.global.config.membership.MembershipProperties;

import jakarta.persistence.EntityManager;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 구독과 기간 및 시도 순서로 잠그고 HTTP 전후 임대를 영속화합니다. */
@Service
@ConditionalOnProperty(name = "membership.billing.enabled", havingValue = "true")
@Transactional(timeout = 5)
public class MembershipWorkStoreImpl implements MembershipWorkStore {
    private final MembershipSubscriptionRepository subscriptions;
    private final MembershipAuthSessionRepository sessions;
    private final MembershipBillingCredentialRepository credentials;
    private final MembershipBillingCycleRepository cycles;
    private final MembershipBillingAttemptRepository attempts;
    private final MembershipEncryptionService encryption;
    private final MembershipCalendar calendar;
    private final MembershipResultOutboxService results;
    private final MembershipProperties policy;
    private final EntityManager em;
    private final Clock clock;
    private final MembershipWithdrawalFenceRepository fences;

    public MembershipWorkStoreImpl(
            MembershipSubscriptionRepository subscriptions,
            MembershipAuthSessionRepository sessions,
            MembershipBillingCredentialRepository credentials,
            MembershipBillingCycleRepository cycles,
            MembershipBillingAttemptRepository attempts,
            MembershipEncryptionService encryption,
            MembershipCalendar calendar,
            MembershipResultOutboxService results,
            MembershipProperties policy,
            EntityManager em,
            Clock clock,
            MembershipWithdrawalFenceRepository fences) {
        this.subscriptions = subscriptions;
        this.sessions = sessions;
        this.credentials = credentials;
        this.cycles = cycles;
        this.attempts = attempts;
        this.encryption = encryption;
        this.calendar = calendar;
        this.results = results;
        this.policy = policy;
        this.em = em;
        this.clock = clock;
        this.fences = fences;
    }

    public MembershipWork claimIssue() {
        if (!policy.recoveryEnabled()) return null;
        var now = clock.instant();
        for (UUID id :
                sessions.findExpiredOpen(
                        now, org.springframework.data.domain.PageRequest.of(0, 10))) {
            var subscriptionId = sessions.findSubscriptionId(id).orElseThrow();
            var s = subscriptions.lock(subscriptionId).orElseThrow();
            var expired = sessions.lock(id).orElseThrow();
            if ("OPEN".equals(expired.state) && !now.isBefore(expired.expiresAt)) {
                expired.state = "EXPIRED";
                expired.authKeyCipher = null;
                if (s.generation == expired.generation && "PENDING".equals(s.state)) {
                    s.state = "EXPIRED";
                    s.autoRenew = false;
                }
            }
        }
        for (UUID id : sessions.findDue(now)) {
            var subscriptionId = sessions.findSubscriptionId(id).orElseThrow();
            var s = subscriptions.lock(subscriptionId).orElseThrow();
            var a = sessions.lock(id).orElseThrow();
            if (!"READY".equals(a.state) || a.nextAttemptAt.isAfter(now) || live(a.leaseUntil))
                continue;
            if (a.attempts == 0 && fences.existsByAccountIdAndCanceledFalse(s.accountId)) {
                a.nextAttemptAt = now.plusSeconds(30);
                continue;
            }
            if (a.generation != s.generation) throw new IllegalStateException("멤버십 세대 불일치");
            if (!Objects.equals(a.providerKeyVersion, policy.providerKeyVersion())) {
                a.state = "MANUAL_REVIEW";
                a.authKeyCipher = null;
                continue;
            }
            if (!now.isBefore(a.expiresAt) || a.attempts >= 10) {
                // 발급 결과가 불명이면 새 인증으로 기존 키를 덮지 않고 대사를 기다립니다.
                a.state = a.attempts > 0 ? "MANUAL_REVIEW" : "EXPIRED";
                a.authKeyCipher = null;
                if (a.attempts == 0) {
                    s.state = "EXPIRED";
                    s.autoRenew = false;
                }
                continue;
            }
            if (!s.autoRenew && a.attempts == 0) {
                a.state = "CANCELED";
                a.authKeyCipher = null;
                continue;
            }
            a.leaseToken = UUID.randomUUID();
            a.leaseUntil = now.plusSeconds(120);
            a.attempts++;
            a.updatedAt = now;
            return new MembershipWork(
                    "ISSUE",
                    s.id,
                    s.generation,
                    a.id,
                    a.leaseToken,
                    decrypt(a.customerKeyCipher, s, "customerKey"),
                    decrypt(a.authKeyCipher, s, "authKey"),
                    null,
                    null,
                    null,
                    a.attempts == 1);
        }
        return null;
    }

    public MembershipWork claimCharge() {
        if (!policy.recoveryEnabled()) return null;
        var now = clock.instant();
        for (UUID id : attempts.findDue(now)) {
            var cycleId = attempts.findCycleId(id).orElseThrow();
            var subscriptionId = cycles.findSubscriptionId(cycleId).orElseThrow();
            var s = subscriptions.lock(subscriptionId).orElseThrow();
            var c = cycles.lock(cycleId).orElseThrow();
            var a = attempts.lock(id).orElseThrow();
            if (!Set.of("PENDING", "UNKNOWN").contains(a.status)
                    || a.nextAttemptAt.isAfter(now)
                    || live(a.leaseUntil)) continue;
            if (c.generation != s.generation) throw new IllegalStateException("멤버십 세대 불일치");
            if (a.amount != c.amount
                    || !Objects.equals(a.currency, c.currency)
                    || !Objects.equals(a.orderId, "sdm-" + a.id)
                    || !Objects.equals(a.idempotencyKey, "sdm-bill-" + a.id)
                    || !Objects.equals(
                            a.requestHash,
                            hash(s.id + ":" + s.generation + ":" + a.amount + ":" + a.id))) {
                a.status = "MANUAL_REVIEW";
                a.lastFailureCode = "BILLING_REQUEST_MISMATCH";
                continue;
            }
            if (!Objects.equals(policy.providerKeyVersion(), a.providerKeyVersion)) {
                a.status = "MANUAL_REVIEW";
                continue;
            }
            boolean first = a.firstRequestedAt == null;
            if (first && fences.existsByAccountIdAndCanceledFalse(s.accountId)) {
                a.nextAttemptAt = now.plusSeconds(30);
                continue;
            }
            if (!first
                    && (a.transportAttempts >= 10
                            || !now.isBefore(a.firstRequestedAt.plus(Duration.ofDays(15))))) {
                a.status = "MANUAL_REVIEW";
                a.leaseToken = null;
                a.leaseUntil = null;
                continue;
            }
            if (first && !s.autoRenew) {
                a.status = "CANCELED";
                c.status = "CANCELED";
                continue;
            }
            var credential = credentials.lock(s.id).orElseThrow();
            if (credential.billingKeyCipher == null
                    || first && !"ACTIVE".equals(credential.deleteState)
                    || !first && !Set.of("ACTIVE", "PENDING").contains(credential.deleteState)) {
                a.status = "MANUAL_REVIEW";
                continue;
            }
            if (first) a.firstRequestedAt = now;
            a.status = "UNKNOWN";
            a.transportAttempts++;
            a.leaseToken = UUID.randomUUID();
            a.leaseUntil = now.plusSeconds(120);
            a.updatedAt = now;
            String customer = decrypt(credential.customerKeyCipher, s, "customerKey");
            return new MembershipWork(
                    first || a.transportAttempts % 2 == 1 ? "CHARGE" : "LOOKUP",
                    s.id,
                    s.generation,
                    a.id,
                    a.leaseToken,
                    customer,
                    null,
                    decrypt(credential.billingKeyCipher, s, "billingKey"),
                    new BillingCharge(a.orderId, a.amount, customer, a.idempotencyKey),
                    a.paymentKey,
                    first);
        }
        return null;
    }

    public MembershipWork claimDelete() {
        if (!policy.recoveryEnabled()) return null;
        for (UUID id : credentials.findDue(clock.instant())) {
            var s = subscriptions.lock(id).orElseThrow();
            if (s.autoRenew
                    || attempts.hasUnresolved(id)
                    || sessions.existsBySubscriptionIdAndStateIn(
                            id, List.of("READY", "MANUAL_REVIEW"))) continue;
            var a = credentials.lock(id).orElseThrow();
            if (!"PENDING".equals(a.deleteState)
                    || live(a.leaseUntil)
                    || a.deleteNextAt.isAfter(clock.instant())) continue;
            if (a.attempts >= 10) {
                a.deleteState = "MANUAL_REVIEW";
                continue;
            }
            a.leaseToken = UUID.randomUUID();
            a.leaseUntil = clock.instant().plusSeconds(120);
            a.attempts++;
            return new MembershipWork(
                    "DELETE",
                    s.id,
                    s.generation,
                    id,
                    a.leaseToken,
                    null,
                    null,
                    decrypt(a.billingKeyCipher, s, "billingKey"),
                    null,
                    null,
                    false);
        }
        return null;
    }

    public void complete(MembershipWork work, BillingReply reply) {
        var s = subscriptions.lock(work.subscriptionId()).orElseThrow();
        if (s.generation != work.generation()) return;
        switch (work.action()) {
            case "ISSUE" -> completeIssue(s, work, reply);
            case "CHARGE", "LOOKUP" -> completeCharge(s, work, reply);
            case "DELETE" -> completeDelete(s, work, reply);
            default -> throw new IllegalArgumentException("멤버십 작업 불일치");
        }
    }

    private void completeIssue(MembershipSubscription s, MembershipWork w, BillingReply reply) {
        var a = sessions.lock(w.workId()).orElseThrow();
        if (!owned(a.leaseToken, a.leaseUntil, w)) return;
        a.leaseToken = null;
        a.leaseUntil = null;
        a.updatedAt = clock.instant();
        if (reply.status() != Status.ISSUED) {
            a.nextAttemptAt = clock.instant().plusSeconds(delay(a.attempts));
            return;
        }
        var credential = credentials.lock(s.id).orElse(null);
        if (credential == null) {
            credential =
                    MembershipBillingCredential.builder()
                            .subscriptionId(s.id)
                            .encryptionKeyId("v1")
                            .deleteState("ACTIVE")
                            .createdAt(clock.instant())
                            .updatedAt(clock.instant())
                            .build();
            em.persist(credential);
        }
        credential.customerKeyCipher = a.customerKeyCipher;
        credential.billingKeyCipher =
                encryption.encrypt(reply.billingKey(), s.id, s.generation, "billingKey");
        credential.deleteState = s.autoRenew ? "ACTIVE" : "PENDING";
        credential.deleteNextAt = clock.instant();
        credential.attempts = 0;
        credential.updatedAt = clock.instant();
        a.state = "ISSUED";
        a.authKeyCipher = null;
        if (s.autoRenew) createCycle(s, 1, clock.instant());
    }

    private void completeCharge(MembershipSubscription s, MembershipWork w, BillingReply reply) {
        var ref = attempts.findById(w.workId()).orElseThrow();
        var c = cycles.lock(ref.cycleId).orElseThrow();
        var a = attempts.lock(w.workId()).orElseThrow();
        if (!owned(a.leaseToken, a.leaseUntil, w) || !"UNKNOWN".equals(a.status)) return;
        a.leaseToken = null;
        a.leaseUntil = null;
        a.updatedAt = clock.instant();
        a.lastFailureCode = reply.failureCode();
        if (reply.status() == Status.APPROVED) {
            a.status = "APPROVED";
            a.paymentKey = reply.paymentKey();
            a.approvedAt = reply.approvedAt();
            if ("APPROVED".equals(c.status)) return;
            c.status = "APPROVED";
            c.succeededAttemptId = a.id;
            c.updatedAt = clock.instant();
            if (c.cycleNumber == 1) {
                var local = reply.approvedAt().atZone(ZoneId.of(s.zone));
                s.anchorDay = local.getDayOfMonth();
                s.anchorLocalTime = local.toLocalTime();
                c.periodStart = reply.approvedAt();
                c.periodEnd =
                        calendar.next(
                                c.periodStart, s.anchorDay, s.anchorLocalTime, ZoneId.of(s.zone));
            }
            s.periodStart = c.periodStart;
            s.periodEnd = c.periodEnd;
            s.entitlementUntil = c.periodEnd;
            s.graceUntil = null;
            s.state = s.autoRenew ? "ACTIVE" : "CANCEL_SCHEDULED";
            s.nextChargeAt = s.autoRenew ? c.periodEnd : null;
            if (!s.autoRenew) requestDelete(s);
            results.record(
                    s,
                    c.cycleNumber == 1 ? "MEMBERSHIP_STARTED" : "MEMBERSHIP_RENEWED",
                    c.cycleNumber);
        } else if (reply.status() == Status.DECLINED) {
            a.status = "DECLINED";
            c.retryNumber = a.attemptNumber;
            if (c.cycleNumber == 1 || !s.autoRenew) {
                c.status = "DECLINED";
                s.state = "EXPIRED";
                s.autoRenew = false;
                s.entitlementUntil = clock.instant();
                s.nextChargeAt = null;
                requestDelete(s);
                results.record(
                        s,
                        c.cycleNumber == 1 && s.canceledAt == null
                                ? "MEMBERSHIP_PAYMENT_FAILED"
                                : "MEMBERSHIP_EXPIRED",
                        c.cycleNumber);
            } else {
                c.status = "RETRY_WAIT";
                s.state = "GRACE";
                s.graceUntil = c.scheduledAt.plus(Duration.ofDays(3)).plusSeconds(120);
                s.entitlementUntil = s.graceUntil;
                c.graceUntil = s.graceUntil;
                c.nextAttemptAt =
                        a.attemptNumber < 3
                                ? c.scheduledAt.plus(Duration.ofDays(a.attemptNumber + 1))
                                : s.graceUntil;
                s.nextChargeAt = c.nextAttemptAt;
                results.record(s, "MEMBERSHIP_PAYMENT_FAILED", c.cycleNumber);
            }
        } else {
            a.nextAttemptAt = clock.instant().plusSeconds(delay(a.transportAttempts));
            if (a.transportAttempts >= 10) a.status = "MANUAL_REVIEW";
        }
    }

    private void completeDelete(MembershipSubscription s, MembershipWork w, BillingReply reply) {
        var a = credentials.lock(s.id).orElseThrow();
        if (!owned(a.leaseToken, a.leaseUntil, w)) return;
        a.leaseToken = null;
        a.leaseUntil = null;
        a.updatedAt = clock.instant();
        if (reply.status() == Status.DELETED) {
            a.deleteState = "DELETED";
            a.billingKeyCipher = null;
            a.customerKeyCipher = null;
            a.lastFailureCode = null;
        } else {
            a.lastFailureCode = "BILLING_DELETE_UNKNOWN";
            a.deleteNextAt = clock.instant().plusSeconds(delay(a.attempts));
            if (a.attempts >= 10) a.deleteState = "MANUAL_REVIEW";
        }
    }

    public boolean advance() {
        var s = subscriptions.lockNextDue(clock.instant()).orElse(null);
        if (s == null) return false;
        long number = cycles.lastNumber(s.id, s.generation);
        if (s.autoRenew
                && s.nextChargeAt != null
                && !s.nextChargeAt.isAfter(clock.instant())
                && policy.renewalEnabled()
                && !attempts.hasUnresolved(s.id)) {
            if ("GRACE".equals(s.state)) {
                var c =
                        cycles.findBySubscriptionIdAndGenerationAndCycleNumber(
                                        s.id, s.generation, number)
                                .orElseThrow();
                c = cycles.lock(c.id).orElseThrow();
                if (c.retryNumber < 3 && clock.instant().isBefore(c.graceUntil)) {
                    createAttempt(s, c, c.retryNumber + 1);
                    c.status = "PENDING";
                    s.nextChargeAt = null;
                    return true;
                }
            } else if ("ACTIVE".equals(s.state)) {
                createCycle(s, number + 1, s.nextChargeAt);
                s.nextChargeAt = null;
                return true;
            }
        }
        if (s.entitlementUntil != null && !clock.instant().isBefore(s.entitlementUntil)) {
            s.state = "EXPIRED";
            s.autoRenew = false;
            s.nextChargeAt = null;
            requestDelete(s);
            results.record(s, "MEMBERSHIP_EXPIRED", number);
        }
        return true;
    }

    private void createCycle(MembershipSubscription s, long number, Instant scheduled) {
        var c =
                MembershipBillingCycle.builder()
                        .id(UUID.randomUUID())
                        .subscriptionId(s.id)
                        .generation(s.generation)
                        .cycleNumber(number)
                        .scheduledAt(scheduled)
                        .periodStart(scheduled)
                        .periodEnd(
                                number == 1
                                        ? null
                                        : calendar.next(
                                                scheduled,
                                                s.anchorDay,
                                                s.anchorLocalTime,
                                                ZoneId.of(s.zone)))
                        .amount(s.priceAmount)
                        .currency(s.currency)
                        .status("PENDING")
                        .retryNumber(0)
                        .createdAt(clock.instant())
                        .updatedAt(clock.instant())
                        .build();
        em.persist(c);
        createAttempt(s, c, 0);
        if (number > 1) {
            // 불명 청구도 유예 상한을 넘겨 혜택을 연장하지 않습니다.
            s.state = "GRACE";
            s.graceUntil = scheduled.plus(Duration.ofDays(3)).plusSeconds(120);
            s.entitlementUntil = s.graceUntil;
            c.graceUntil = s.graceUntil;
            results.record(s, "MEMBERSHIP_RENEWAL_PENDING", number);
        }
    }

    private void createAttempt(MembershipSubscription s, MembershipBillingCycle c, int number) {
        UUID id = UUID.randomUUID();
        var a =
                MembershipBillingAttempt.builder()
                        .id(id)
                        .cycleId(c.id)
                        .attemptNumber(number)
                        .orderId("sdm-" + id)
                        .idempotencyKey("sdm-bill-" + id)
                        .status("PENDING")
                        .amount(c.amount)
                        .currency(c.currency)
                        .providerKeyVersion(policy.providerKeyVersion())
                        .requestHash(hash(s.id + ":" + s.generation + ":" + c.amount + ":" + id))
                        .nextAttemptAt(clock.instant())
                        .createdAt(clock.instant())
                        .updatedAt(clock.instant())
                        .build();
        em.persist(a);
    }

    private void requestDelete(MembershipSubscription s) {
        var a = credentials.lock(s.id).orElse(null);
        if (a != null && "ACTIVE".equals(a.deleteState)) {
            a.deleteState = "PENDING";
            a.deleteNextAt = clock.instant();
            a.updatedAt = clock.instant();
        }
    }

    private boolean owned(UUID token, Instant until, MembershipWork w) {
        return Objects.equals(token, w.leaseToken()) && live(until);
    }

    private boolean live(Instant until) {
        return until != null && clock.instant().isBefore(until);
    }

    private String decrypt(String cipher, MembershipSubscription s, String field) {
        return encryption.decrypt(cipher, s.id, s.generation, field);
    }

    private long delay(int attempt) {
        return switch (attempt) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 30;
            default -> 60;
        };
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 미지원");
        }
    }
}
