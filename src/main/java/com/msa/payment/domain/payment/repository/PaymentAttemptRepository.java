package com.msa.payment.domain.payment.repository;

import com.msa.payment.domain.payment.entity.PaymentAttempt;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 결제 원본 조회·잠금 SQL을 캡슐화합니다. 다른 서비스 DB는 조회하지 않습니다. */
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAttempt a where a.id=:id")
    Optional<PaymentAttempt> lock(@Param("id") UUID id);

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value = "select true from pg_advisory_xact_lock(hashtextextended(:identity,711))",
            nativeQuery = true)
    boolean lockCommandIdentity(@Param("identity") String identity);

    /** 중복 검증에는 ID만 읽어 잠금 전 원본 엔티티가 캐시에 적재되지 않게 합니다. */
    @Query("select a.id from PaymentAttempt a where a.orderId=:order")
    Optional<UUID> findIdByOrderId(@Param("order") UUID order);

    @Query("select a.id from PaymentAttempt a where a.paymentKey=:key")
    Optional<UUID> findIdByPaymentKey(@Param("key") String key);

    /** 작업 분배와 행 잠금을 한 SQL로 처리하고 임대 변경은 관리 엔티티 변경 감지로 저장합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value =
                    """
                    select * from payment.payment_attempts
                    where next_attempt_at<=:now and (lease_until is null or lease_until<=:now)
                      and (status in ('PENDING','UNKNOWN') or (status='APPROVED' and cancellation_requested))
                    order by next_attempt_at,id limit 1 for update skip locked
                    """,
            nativeQuery = true)
    Optional<PaymentAttempt> lockNextDue(@Param("now") Instant now);
}
