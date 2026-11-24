package com.msa.payment.domain.membership.repository;

import com.msa.payment.domain.membership.entity.MembershipSubscription;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 구독을 먼저 잠가 세션 생성과 청구 및 해지의 선형화 지점을 유지합니다. */
public interface MembershipSubscriptionRepository
        extends JpaRepository<MembershipSubscription, UUID> {
    Optional<MembershipSubscription> findByAccountId(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MembershipSubscription s where s.id=:id")
    Optional<MembershipSubscription> lock(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MembershipSubscription s where s.accountId=:account")
    Optional<MembershipSubscription> lockAccount(@Param("account") UUID account);

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value = "select true from pg_advisory_xact_lock(hashtextextended(:identity,712))",
            nativeQuery = true)
    boolean lockIdentity(@Param("identity") String identity);

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value =
                    """
                    select * from payment.membership_subscriptions
                    where state in ('ACTIVE','GRACE','CANCEL_SCHEDULED')
                      and ((auto_renew and next_charge_at<=:now) or entitlement_until<=:now)
                    order by next_charge_at nulls last,id limit 1 for update skip locked
                    """,
            nativeQuery = true)
    Optional<MembershipSubscription> lockNextDue(@Param("now") Instant now);
}
