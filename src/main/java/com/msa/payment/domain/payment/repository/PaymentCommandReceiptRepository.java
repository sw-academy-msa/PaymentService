package com.msa.payment.domain.payment.repository;

import com.msa.payment.domain.payment.entity.PaymentCommandReceipt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 명령 처리 이력의 중복 확인을 담당합니다. 신규 저장은 기존 persist와 동일 트랜잭션입니다. */
public interface PaymentCommandReceiptRepository
        extends JpaRepository<PaymentCommandReceipt, UUID> {
    boolean existsByAttemptIdAndCommandVersion(UUID attemptId, long commandVersion);
}
