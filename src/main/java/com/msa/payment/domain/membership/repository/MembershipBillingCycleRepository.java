package com.msa.payment.domain.membership.repository;

import com.msa.payment.domain.membership.entity.MembershipBillingCycle;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

/** 구독별 세대와 청구 순번의 유일성을 DB 제약과 함께 적용합니다. */
public interface MembershipBillingCycleRepository
        extends JpaRepository<MembershipBillingCycle, UUID> {
    @Query("select c.subscriptionId from MembershipBillingCycle c where c.id=:id")
    Optional<UUID> findSubscriptionId(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MembershipBillingCycle c where c.id=:id")
    Optional<MembershipBillingCycle> lock(@Param("id") UUID id);

    Optional<MembershipBillingCycle> findBySubscriptionIdAndGenerationAndCycleNumber(
            UUID id, long generation, long cycleNumber);

    @Query(
            "select coalesce(max(c.cycleNumber),0) from MembershipBillingCycle c where"
                    + " c.subscriptionId=:id and c.generation=:generation")
    long lastNumber(@Param("id") UUID id, @Param("generation") long generation);
}
