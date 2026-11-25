package com.msa.payment.domain.membership.dto.request;

import jakarta.validation.constraints.*;

/** SDK 인증 결과는 본인 세션에만 결합하며 원문 문자열 표현을 금지합니다. */
public record MembershipCompletionRequest(
        @NotBlank @Size(max = 1000) String authKey,
        @NotBlank @Size(max = 1000) String customerKey) {
    @Override
    public String toString() {
        return "MembershipCompletionRequest[REDACTED]";
    }
}
