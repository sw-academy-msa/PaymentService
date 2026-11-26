package com.msa.payment.domain.advertising.repository;

import com.msa.payment.domain.advertising.entity.AdvertisingCommandReceipt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

/** 취소 선도착과 중복 명령의 식별자를 영속 조회합니다. */
public interface AdvertisingCommandReceiptRepository
        extends JpaRepository<AdvertisingCommandReceipt, UUID> {
    List<AdvertisingCommandReceipt> findByApplicationIdAndEventType(
            UUID applicationId, String eventType);

    boolean existsByApplicationIdAndAggregateVersion(UUID applicationId, long aggregateVersion);
}
