package com.msa.payment.domain.analytics.repository;

import com.msa.payment.domain.analytics.entity.GaDispatch;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 짧은 DB 임대 후 HTTP 동안 연결을 반납하고 오래된 작업 결과는 버립니다. */
public interface GaDispatchRepository extends JpaRepository<GaDispatch, GaDispatch.Key> {
    @Query(
            value =
                    """
                    select * from payment.ga_purchase_dispatch d
                    where d.status='PENDING' and d.next_attempt_at <= :now
                    and (d.lease_until is null or d.lease_until <= :now)
                    and (d.kind='PURCHASE' or not exists
                        (select 1 from payment.ga_purchase_dispatch p where p.order_id=d.order_id
                         and p.kind='PURCHASE' and p.status='PENDING'))
                    order by d.created_at, d.kind limit 1 for update skip locked
                    """,
            nativeQuery = true)
    Optional<GaDispatch> next(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from GaDispatch d where d.orderId=:order and d.kind=:kind")
    Optional<GaDispatch> lock(@Param("order") UUID order, @Param("kind") String kind);

    @Modifying
    @Query("update GaDispatch d set d.payload=null where d.finishedAt < :before")
    int purge(@Param("before") Instant before);
}
