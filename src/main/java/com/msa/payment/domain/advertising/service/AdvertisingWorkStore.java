package com.msa.payment.domain.advertising.service;

import com.msa.payment.domain.advertising.dto.response.AdvertisingWork;
import com.msa.payment.domain.payment.dto.response.ProviderReply;

/** 외부 HTTP 앞뒤의 짧은 트랜잭션과 임대 복구를 분리합니다. */
public interface AdvertisingWorkStore {
    AdvertisingWork claim();

    void complete(AdvertisingWork work, ProviderReply reply);
}
