package com.msa.payment.domain.withdrawal.dto.response;

import lombok.Builder;

/** 내부 HTTP 응답입니다. 원본 결과가 없거나 불명확한 결제를 0건으로 취급하지 않습니다. */
@Builder
public record PaymentClearanceResponse(long pendingPayments) {}
