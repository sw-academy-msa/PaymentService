package com.msa.payment.domain.advertising.service;

import com.msa.payment.domain.advertising.entity.AdvertisingPayment;

import java.util.UUID;

/** 금융 상태와 전용 결과 이벤트를 같은 로컬 트랜잭션에 기록합니다. */
public interface AdvertisingResultOutboxService {
    void record(AdvertisingPayment payment, String type, UUID cause);
}
