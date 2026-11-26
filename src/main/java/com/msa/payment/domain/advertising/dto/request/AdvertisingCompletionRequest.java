package com.msa.payment.domain.advertising.dto.request;

import jakarta.validation.constraints.*;

/** 브라우저 금액을 받지 않고 원본 결제의 공급자 식별자만 접수합니다. */
public record AdvertisingCompletionRequest(
        @NotBlank @Size(max = 200) String paymentKey,
        @NotBlank @Pattern(regexp = "AD-[a-f0-9-]{36}") String tossOrderId) {
    @Override
    public String toString() {
        return "AdvertisingCompletionRequest[REDACTED]";
    }
}
