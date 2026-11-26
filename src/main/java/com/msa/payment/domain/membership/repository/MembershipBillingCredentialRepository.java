package com.msa.payment.domain.membership.repository;

import com.msa.payment.domain.membership.entity.MembershipBillingCredential;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

/** 삭제 재시도와 카드 교체 뒤 이전 키 삭제에도 공급자 확인 전 암호문을 유지합니다. */
public interface MembershipBillingCredentialRepository
        extends JpaRepository<MembershipBillingCredential, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MembershipBillingCredential c where c.subscriptionId=:id")
    Optional<MembershipBillingCredential> lock(@Param("id") UUID id);

    @Query(
            value =
                    """
                    select subscription_id from payment.membership_billing_credentials
                    where ((delete_state='PENDING' and delete_next_at<=:now)
                        or (retired_billing_key_cipher is not null and retired_next_at<=:now))
                      and (lease_until is null or lease_until<=:now)
                    order by coalesce(retired_next_at,delete_next_at),subscription_id limit 10
                    """,
            nativeQuery = true)
    List<UUID> findDue(@Param("now") Instant now);
}
