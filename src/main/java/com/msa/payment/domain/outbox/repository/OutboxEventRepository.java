package com.msa.payment.domain.outbox.repository;

import com.msa.payment.domain.outbox.entity.OutboxEvent;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 발행 대상 선택과 PostgreSQL 잠금 SQL을 캡슐화합니다. 결과는 관리 엔티티이므로 변경 감지로 저장합니다. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    /** 원본 payload 없이 해당 서비스 소유 Outbox의 미발행 집계만 읽습니다. */
    @org.springframework.data.jpa.repository.Query(
            "select count(e) as pending, min(e.createdAt) as oldest from OutboxEvent e where"
                + " e.publishedAt is null")
    com.msa.payment.domain.outbox.dto.response.OutboxMetricsSnapshot monitoringSnapshot();

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value =
                    """
                    select * from payment.outbox_events
                    where published_at is null and next_attempt_at <= :now
                    order by created_at,id limit 1 for update skip locked
                    """,
            nativeQuery = true)
    Optional<OutboxEvent> lockNextPending(@Param("now") Instant now);
}
