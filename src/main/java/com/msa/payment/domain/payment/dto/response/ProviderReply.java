package com.msa.payment.domain.payment.dto.response;

import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;

import lombok.Builder;

import java.time.Instant;

/** 검증된 토스 상태를 내부 저장 계층으로 전달합니다. 원문 응답·개인정보는 보관하지 않습니다. */
@Builder
public record ProviderReply(
        Status status, Instant approvedAt, String paymentKey, String failureCode) {
    /** 빌더에 담긴 개인정보·결제키·토큰·이벤트 본문을 로그에 노출하지 않습니다. */
    public static class ProviderReplyBuilder {
        @Override
        public String toString() {
            return "ProviderReplyBuilder[REDACTED]";
        }
    }

    public static ProviderReply unknown(String code) {
        return ProviderReply.builder()
                .status(Status.UNKNOWN)
                .approvedAt(null)
                .paymentKey(null)
                .failureCode(code)
                .build();
    }

    @Override
    public String toString() {
        return "ProviderReply[status=" + status + "]";
    }
}
