package com.msa.payment.domain.membership.service.impl;

import com.msa.payment.domain.membership.entity.*;
import com.msa.payment.domain.membership.exception.*;
import com.msa.payment.domain.membership.repository.*;
import com.msa.payment.domain.membership.service.*;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** 기존 탈퇴 임대의 작업 ID로 새 가입과 청구를 차단하고 무환불 종료를 확정합니다. */
@Service
@Transactional(timeout = 5)
public class MembershipWithdrawalServiceImpl implements MembershipWithdrawalService {
    private final MembershipSubscriptionRepository subscriptions;
    private final MembershipWithdrawalFenceRepository fences;
    private final MembershipBillingCredentialRepository credentials;
    private final MembershipResultOutboxService results;
    private final EntityManager em;
    private final Clock clock;

    public MembershipWithdrawalServiceImpl(
            MembershipSubscriptionRepository subscriptions,
            MembershipWithdrawalFenceRepository fences,
            MembershipBillingCredentialRepository credentials,
            MembershipResultOutboxService results,
            EntityManager em,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.fences = fences;
        this.credentials = credentials;
        this.results = results;
        this.em = em;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public long inspect(UUID account) {
        var s = subscriptions.findByAccountId(account).orElse(null);
        if (s == null) return 0;
        return pending(s.id);
    }

    public long prepare(UUID account, UUID operation) {
        subscriptions.lockIdentity("account:" + account);
        subscriptions.lockAccount(account);
        var fence = fences.lock(operation).orElse(null);
        if (fence == null) {
            fence = MembershipWithdrawalFence.builder().id(operation).accountId(account).build();
            em.persist(fence);
        } else if (!fence.accountId.equals(account) || fence.canceled) throw conflict();
        return inspect(account);
    }

    public long finalizeWithdrawal(UUID account, UUID operation) {
        subscriptions.lockIdentity("account:" + account);
        var s = subscriptions.lockAccount(account).orElse(null);
        var fence = fences.lock(operation).orElseThrow(this::conflict);
        if (!fence.accountId.equals(account) || fence.canceled) throw conflict();
        if (fence.finalized) return 0;
        if (s != null) {
            long pending = pending(s.id);
            if (pending > 0) return pending;
            s.autoRenew = false;
            s.state = "EXPIRED";
            s.entitlementUntil = clock.instant();
            s.nextChargeAt = null;
            s.canceledAt = clock.instant();
            s.graceUntil = null;
            em.createQuery(
                            "update MembershipBillingAttempt a set a.status='CANCELED' where"
                                + " a.firstRequestedAt is null and a.cycleId in (select c.id from"
                                + " MembershipBillingCycle c where c.subscriptionId=:id)")
                    .setParameter("id", s.id)
                    .executeUpdate();
            em.createQuery(
                            "update MembershipAuthSession a set"
                                    + " a.state='CANCELED',a.authKeyCipher=null where"
                                    + " a.subscriptionId=:id and a.attempts=0 and a.state in"
                                    + " ('OPEN','READY')")
                    .setParameter("id", s.id)
                    .executeUpdate();
            var credential = credentials.lock(s.id).orElse(null);
            if (credential != null && "ACTIVE".equals(credential.deleteState)) {
                credential.deleteState = "PENDING";
                credential.deleteNextAt = clock.instant();
            }
            results.record(s, "MEMBERSHIP_WITHDRAWN", 0);
        }
        fence.finalized = true;
        return 0;
    }

    public void release(UUID account, UUID operation) {
        subscriptions.lockIdentity("account:" + account);
        subscriptions.lockAccount(account);
        var fence = fences.lock(operation).orElse(null);
        if (fence == null) {
            fence = MembershipWithdrawalFence.builder().id(operation).accountId(account).build();
            em.persist(fence);
        }
        if (!fence.accountId.equals(account)) throw conflict();
        // 이미 수행한 무환불 해지는 되돌리지 않습니다. 실패한 계정 탈퇴는 새 가입을 허용합니다.
        fence.canceled = true;
    }

    private long pending(UUID subscription) {
        long attempts =
                em.createQuery(
                                "select count(a) from MembershipBillingAttempt a,"
                                    + " MembershipBillingCycle c where a.cycleId=c.id and"
                                    + " c.subscriptionId=:id and a.firstRequestedAt is not null and"
                                    + " a.status in ('UNKNOWN','MANUAL_REVIEW')",
                                Long.class)
                        .setParameter("id", subscription)
                        .getSingleResult();
        long sessions =
                em.createQuery(
                                "select count(a) from MembershipAuthSession a where"
                                        + " a.subscriptionId=:id and a.attempts>0 and a.state in"
                                        + " ('READY','MANUAL_REVIEW')",
                                Long.class)
                        .setParameter("id", subscription)
                        .getSingleResult();
        return Math.addExact(attempts, sessions);
    }

    private MembershipException conflict() {
        return new MembershipException(MembershipErrorCode.CONFLICT);
    }
}
