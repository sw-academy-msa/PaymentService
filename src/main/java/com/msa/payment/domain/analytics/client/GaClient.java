package com.msa.payment.domain.analytics.client;

import com.msa.payment.domain.analytics.dto.response.GaWork;

/** 동의 확인과 MP 전송을 DB 트랜잭션 밖에서 수행합니다. */
public interface GaClient {
    String send(GaWork work);
}
