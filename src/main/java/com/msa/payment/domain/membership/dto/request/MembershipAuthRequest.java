package com.msa.payment.domain.membership.dto.request;

import jakarta.validation.constraints.*;

/** 현재 요금제와 정기결제 약관 동의만 받고 청구 금액은 서버가 결정합니다. */
public record MembershipAuthRequest(
        @NotBlank @Size(max = 40) String planVersion,
        @NotBlank @Size(max = 40) String termsVersion,
        @AssertTrue boolean recurringPaymentAgreed) {
    @Override
    public String toString() {
        return "MembershipAuthRequest[REDACTED]";
    }
}
