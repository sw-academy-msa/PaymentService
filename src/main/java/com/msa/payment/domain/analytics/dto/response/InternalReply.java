package com.msa.payment.domain.analytics.dto.response;

/** 내부 서비스 호출의 상태 코드와 본문입니다. HTTP 응답 DTO가 아니며 해석과 검증은 호출 측이 합니다. 본문은 로그에 남기지 않습니다. */
public record InternalReply(int status, String body) {
    @Override
    public String toString() {
        return "InternalReply[status=" + status + ", body=REDACTED]";
    }
}
