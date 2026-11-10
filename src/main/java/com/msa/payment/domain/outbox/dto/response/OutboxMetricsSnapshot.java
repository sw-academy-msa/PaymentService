package com.msa.payment.domain.outbox.dto.response;

import java.time.Instant;

/** HTTP 응답이 아닌 JPA 집계 투영입니다. 원본 이벤트/수신자 정보는 반환하지 않습니다. */
public interface OutboxMetricsSnapshot {
    long getPending();

    Instant getOldest();
}
