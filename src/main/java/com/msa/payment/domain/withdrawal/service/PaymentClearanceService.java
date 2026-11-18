package com.msa.payment.domain.withdrawal.service;

import com.msa.payment.domain.withdrawal.dto.request.PaymentClearanceRequest;
import com.msa.payment.domain.withdrawal.dto.response.PaymentClearanceResponse;

/** 거래 차단된 Order의 결제 참조를 Payment 원본으로 대조합니다. */
public interface PaymentClearanceService {
    PaymentClearanceResponse inspect(PaymentClearanceRequest request);
}
