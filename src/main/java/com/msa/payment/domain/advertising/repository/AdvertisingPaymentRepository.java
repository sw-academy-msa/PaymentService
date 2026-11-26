package com.msa.payment.domain.advertising.repository;

import com.msa.payment.domain.advertising.entity.AdvertisingPayment;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 광고 회차 식별자와 금융 작업 잠금을 한 서비스 DB 안에서 관리합니다. */
public interface AdvertisingPaymentRepository extends JpaRepository<AdvertisingPayment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AdvertisingPayment p where p.id=:id")
    Optional<AdvertisingPayment> lock(@Param("id") UUID id);

    Optional<AdvertisingPayment> findByApplicationId(UUID applicationId);

    boolean existsByPaymentKeyAndIdNot(String paymentKey, UUID id);

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value = "select true from pg_advisory_xact_lock(hashtextextended(:identity,712))",
            nativeQuery = true)
    boolean lockIdentity(@Param("identity") String identity);

    /** 실행 이력이 없는 만료와 취소도 잡에서 정리하며 결과 불명은 계속 조회합니다. */
    @Query(
            value =
                    """
                    select * from payment.advertising_payments
                    where next_attempt_at<=:now and (lease_until is null or lease_until<=:now)
                      and ((status='PENDING' and (approval_requested or cancellation_requested or expires_at<=:now))
                        or (status='PAID' and cancellation_requested))
                    order by next_attempt_at,id limit 1 for update skip locked
                    """,
            nativeQuery = true)
    Optional<AdvertisingPayment> lockNextDue(@Param("now") Instant now);
}
