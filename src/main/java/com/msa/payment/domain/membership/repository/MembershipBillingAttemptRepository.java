package com.msa.payment.domain.membership.repository;

import com.msa.payment.domain.membership.entity.MembershipBillingAttempt;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

/** 작업 후보 조회 뒤 구독과 기간을 먼저 잠그고 동일 시도를 재확인합니다. */
public interface MembershipBillingAttemptRepository
        extends JpaRepository<MembershipBillingAttempt, UUID> {
    @Query("select a.cycleId from MembershipBillingAttempt a where a.id=:id")
    Optional<UUID> findCycleId(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MembershipBillingAttempt a where a.id=:id")
    Optional<MembershipBillingAttempt> lock(@Param("id") UUID id);

    @Query(
            value =
                    """
                    select a.id from payment.membership_billing_attempts a
                    where a.status in ('PENDING','UNKNOWN') and a.next_attempt_at<=:now
                      and (a.lease_until is null or a.lease_until<=:now)
                    order by a.next_attempt_at,a.id limit 10
                    """,
            nativeQuery = true)
    List<UUID> findDue(@Param("now") Instant now);

    @Query(
            "select count(a)>0 from MembershipBillingAttempt a, MembershipBillingCycle c where"
                    + " a.cycleId=c.id and c.subscriptionId=:id and a.status in"
                    + " ('PENDING','UNKNOWN','MANUAL_REVIEW')")
    boolean hasUnresolved(@Param("id") UUID id);
}
