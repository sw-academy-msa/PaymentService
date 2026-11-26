package com.msa.payment.domain.membership.service.impl;

import com.msa.payment.domain.membership.dto.request.*;
import com.msa.payment.domain.membership.dto.response.*;
import com.msa.payment.domain.membership.entity.*;
import com.msa.payment.domain.membership.exception.*;
import com.msa.payment.domain.membership.repository.*;
import com.msa.payment.domain.membership.service.*;
import com.msa.payment.global.config.membership.*;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** HTTP 중 금융 공급자를 호출하지 않고 본인 동의와 암호화 세션만 원자적으로 접수합니다. 유예 중 카드 교체도 같은 세션으로 받습니다. */
@Service
@Transactional(timeout = 5)
public class MembershipServiceImpl implements MembershipService {
    private final MembershipSubscriptionRepository subscriptions;
    private final MembershipAuthSessionRepository sessions;
    private final MembershipBillingCredentialRepository credentials;
    private final MembershipBillingAttemptRepository attempts;
    private final MembershipBillingCycleRepository cycles;
    private final EntityManager em;
    private final ObjectProvider<MembershipEncryptionService> encryption;
    private final MembershipProperties policy;
    private final BillingProperties billing;
    private final Clock clock;
    private final MembershipWithdrawalFenceRepository fences;

    public MembershipServiceImpl(
            MembershipSubscriptionRepository subscriptions,
            MembershipAuthSessionRepository sessions,
            MembershipBillingCredentialRepository credentials,
            MembershipBillingAttemptRepository attempts,
            MembershipBillingCycleRepository cycles,
            EntityManager em,
            ObjectProvider<MembershipEncryptionService> encryption,
            MembershipProperties policy,
            BillingProperties billing,
            Clock clock,
            MembershipWithdrawalFenceRepository fences) {
        this.subscriptions = subscriptions;
        this.sessions = sessions;
        this.credentials = credentials;
        this.attempts = attempts;
        this.cycles = cycles;
        this.em = em;
        this.encryption = encryption;
        this.policy = policy;
        this.billing = billing;
        this.clock = clock;
        this.fences = fences;
    }

    @Transactional(readOnly = true)
    public MembershipConfigurationResponse configuration() {
        available();
        return new MembershipConfigurationResponse(
                billing.clientKey(),
                policy.planVersion(),
                policy.amount(),
                "KRW",
                policy.termsVersion(),
                policy.successUrl(),
                policy.failUrl());
    }

    public MembershipSessionResponse create(UUID account, UUID key, MembershipAuthRequest request) {
        available();
        // 아직 없는 구독도 직렬화하고 타 계정의 같은 멱등 키 재사용을 거절합니다.
        new TreeSet<>(List.of("account:" + account, "session-key:" + key))
                .forEach(subscriptions::lockIdentity);
        if (fences.existsByAccountIdAndCanceledFalse(account)) conflict();
        String hash =
                hash(
                        request.planVersion()
                                + ":"
                                + request.termsVersion()
                                + ":"
                                + request.recurringPaymentAgreed());
        var previous = sessions.findByIdempotencyKey(key.toString()).orElse(null);
        if (previous != null) {
            if (!previous.accountId.equals(account) || !previous.payloadHash.equals(hash))
                conflict();
            if (!"OPEN".equals(previous.state) || !clock.instant().isBefore(previous.expiresAt))
                conflict();
            return response(previous);
        }
        if (!request.recurringPaymentAgreed()
                || !policy.planVersion().equals(request.planVersion())
                || !policy.termsVersion().equals(request.termsVersion())
                || policy.amount() <= 0)
            throw new MembershipException(MembershipErrorCode.INVALID_CONSENT);
        var now = clock.instant();
        var s = subscriptions.lockAccount(account).orElse(null);
        if (s == null) {
            s =
                    MembershipSubscription.builder()
                            .id(UUID.randomUUID())
                            .accountId(account)
                            .generation(1)
                            .state("PENDING")
                            .autoRenew(true)
                            .planVersion(policy.planVersion())
                            .priceAmount(policy.amount())
                            .currency("KRW")
                            .zone("Asia/Seoul")
                            .anchorDay(1)
                            .termsVersion(policy.termsVersion())
                            .agreedAt(now)
                            .agreedAccountId(account)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
            em.persist(s);
        } else if ("GRACE".equals(s.state)) {
            var credential = credentials.lock(s.id).orElse(null);
            var cycle =
                    cycles.findBySubscriptionIdAndGenerationAndCycleNumber(
                                    s.id, s.generation, cycles.lastNumber(s.id, s.generation))
                            .orElse(null);
            // 결제 실패 유예 중에는 같은 세대에서 카드만 교체합니다. 진행 중 청구가 있으면 중복 결제를 막습니다.
            if (!s.autoRenew
                    || s.graceUntil == null
                    || !now.isBefore(s.graceUntil)
                    || attempts.hasUnresolved(s.id)
                    // 정기 청구와 재시도를 합한 최대 4회를 모두 쓴 뒤에는 새 청구를 만들지 않습니다.
                    || cycle == null
                    || cycle.retryNumber >= 3
                    || sessions.existsBySubscriptionIdAndStateIn(
                            s.id, List.of("OPEN", "READY", "MANUAL_REVIEW"))
                    || credential == null
                    || !"ACTIVE".equals(credential.deleteState)
                    || credential.billingKeyCipher == null
                    || credential.retiredBillingKeyCipher != null
                    || live(credential.leaseUntil)) conflict();
        } else {
            var credential = credentials.lock(s.id).orElse(null);
            if (!Set.of("NONE", "EXPIRED").contains(s.state)
                    || attempts.hasUnresolved(s.id)
                    || sessions.existsBySubscriptionIdAndStateIn(
                            s.id, List.of("OPEN", "READY", "MANUAL_REVIEW"))
                    || credential != null && !"DELETED".equals(credential.deleteState)
                    // 이전 세대로 암호화한 교체 전 키가 남아 있으면 세대를 올리지 않습니다.
                    || credential != null && credential.retiredBillingKeyCipher != null) conflict();
            s.generation = Math.addExact(s.generation, 1);
            s.state = "PENDING";
            s.autoRenew = true;
            s.planVersion = policy.planVersion();
            s.priceAmount = policy.amount();
            s.termsVersion = policy.termsVersion();
            s.agreedAt = now;
            s.agreedAccountId = account;
            s.periodStart = null;
            s.periodEnd = null;
            s.entitlementUntil = null;
            s.graceUntil = null;
            s.nextChargeAt = null;
            s.canceledAt = null;
            s.updatedAt = now;
        }
        String customer = "sdm-" + UUID.randomUUID();
        var session =
                MembershipAuthSession.builder()
                        .id(UUID.randomUUID())
                        .accountId(account)
                        .subscriptionId(s.id)
                        .generation(s.generation)
                        .customerKeyCipher(
                                crypto().encrypt(customer, s.id, s.generation, "customerKey"))
                        .encryptionKeyId("v1")
                        .providerKeyVersion(policy.providerKeyVersion())
                        .state("OPEN")
                        .expiresAt(now.plusSeconds(600))
                        .idempotencyKey(key.toString())
                        .payloadHash(hash)
                        .nextAttemptAt(now)
                        .termsVersion(s.termsVersion)
                        .agreedAt(now)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        em.persist(session);
        return response(session);
    }

    public MembershipOperationResponse complete(
            UUID account, UUID sessionId, UUID key, MembershipCompletionRequest request) {
        available();
        var subscriptionId =
                sessions.findSubscriptionId(sessionId)
                        .orElseThrow(
                                () -> new MembershipException(MembershipErrorCode.INVALID_SESSION));
        var s = subscriptions.lock(subscriptionId).orElseThrow();
        if (fences.existsByAccountIdAndCanceledFalse(account)) conflict();
        if (!s.accountId.equals(account))
            throw new MembershipException(MembershipErrorCode.FORBIDDEN);
        var session = sessions.lock(sessionId).orElseThrow();
        String hash =
                hash(
                        request.authKey().length()
                                + ":"
                                + request.authKey()
                                + ":"
                                + request.customerKey());
        if (session.completionHash != null) {
            if (!session.completionHash.equals(hash) || !key.equals(session.completionKey))
                conflict();
            return new MembershipOperationResponse(s.id, session.state, true);
        }
        if (!"OPEN".equals(session.state)
                || session.generation != s.generation
                || !s.autoRenew
                || !clock.instant().isBefore(session.expiresAt)
                || !MessageDigest.isEqual(
                        request.customerKey().getBytes(StandardCharsets.UTF_8),
                        crypto().decrypt(
                                        session.customerKeyCipher,
                                        s.id,
                                        s.generation,
                                        "customerKey")
                                .getBytes(StandardCharsets.UTF_8)))
            throw new MembershipException(MembershipErrorCode.INVALID_SESSION);
        session.authKeyCipher = crypto().encrypt(request.authKey(), s.id, s.generation, "authKey");
        session.completionHash = hash;
        session.completionKey = key;
        session.state = "READY";
        session.nextAttemptAt = clock.instant();
        session.updatedAt = clock.instant();
        return new MembershipOperationResponse(s.id, "PENDING", true);
    }

    @Transactional(readOnly = true)
    public MembershipResponse get(UUID account) {
        return MembershipResponse.from(subscriptions.findByAccountId(account).orElse(null));
    }

    private MembershipSessionResponse response(MembershipAuthSession s) {
        return new MembershipSessionResponse(
                s.id,
                crypto().decrypt(
                                s.customerKeyCipher, s.subscriptionId, s.generation, "customerKey"),
                billing.clientKey(),
                s.expiresAt,
                policy.successUrl(),
                policy.failUrl());
    }

    private boolean live(Instant until) {
        return until != null && clock.instant().isBefore(until);
    }

    private void available() {
        if (!policy.apiEnabled() || !billing.enabled() || encryption.getIfAvailable() == null)
            throw new MembershipException(MembershipErrorCode.UNAVAILABLE);
    }

    private MembershipEncryptionService crypto() {
        return encryption.getObject();
    }

    private void conflict() {
        throw new MembershipException(MembershipErrorCode.CONFLICT);
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
