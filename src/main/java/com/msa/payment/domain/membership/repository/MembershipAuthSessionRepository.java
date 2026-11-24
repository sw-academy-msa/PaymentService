package com.msa.payment.domain.membership.repository;

import com.msa.payment.domain.membership.entity.MembershipAuthSession;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

/** 구독 잠금 이후 인증 세션을 잠그며 작업 후보 탐색은 식별자만 반환합니다. */
public interface MembershipAuthSessionRepository
        extends JpaRepository<MembershipAuthSession, UUID> {
    Optional<MembershipAuthSession> findByIdempotencyKey(String key);

    @Query("select s.subscriptionId from MembershipAuthSession s where s.id=:id")
    Optional<UUID> findSubscriptionId(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MembershipAuthSession s where s.id=:id")
    Optional<MembershipAuthSession> lock(@Param("id") UUID id);

    @Query(
            value =
                    """
                    select id from payment.membership_auth_sessions
                    where state='READY' and next_attempt_at<=:now and (lease_until is null or lease_until<=:now)
                    order by next_attempt_at,id limit 10
                    """,
            nativeQuery = true)
    List<UUID> findDue(@Param("now") Instant now);

    boolean existsBySubscriptionIdAndStateIn(UUID id, Collection<String> states);

    @Query(
            "select s.id from MembershipAuthSession s where s.state='OPEN' and s.expiresAt<=:now"
                    + " order by s.expiresAt")
    List<UUID> findExpiredOpen(
            @Param("now") Instant now, org.springframework.data.domain.Pageable page);
}
