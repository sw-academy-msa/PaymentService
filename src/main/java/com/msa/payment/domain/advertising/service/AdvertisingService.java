package com.msa.payment.domain.advertising.service;

import com.msa.payment.domain.advertising.dto.request.AdvertisingCompletionRequest;
import com.msa.payment.domain.advertising.dto.response.*;

import java.util.UUID;

/** 판매자 본인의 금융 조회와 승인 접수만 공개합니다. */
public interface AdvertisingService {
    AdvertisingCheckoutResponse checkout(UUID seller, UUID payment);

    AdvertisingPaymentResponse get(UUID seller, UUID payment);

    AdvertisingPaymentResponse complete(
            UUID seller, UUID payment, UUID key, AdvertisingCompletionRequest request);
}
