package com.msa.payment.domain.withdrawal.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.payment.domain.payment.entity.PaymentAttempt;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.domain.payment.repository.PaymentAttemptRepository;
import com.msa.payment.domain.withdrawal.dto.request.PaymentClearanceRequest;
import com.msa.payment.domain.withdrawal.service.PaymentClearanceService;
import com.msa.payment.global.config.database.PaymentDatabaseTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.*;
import java.util.*;

/** 결과 불명과 보상 중 거래, 없는 결제를 정산 완료로 오인하지 않는지 원본 DB로 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
class PaymentClearanceServiceIntegrationTest extends PaymentDatabaseTestSupport {
    @Autowired PaymentClearanceService service;
    @Autowired PaymentAttemptRepository attempts;

    @Test
    void validatesOriginalTerminalResultAndOrderIdentity() {
        UUID id = UUID.randomUUID(), order = UUID.randomUUID();
        var row =
                PaymentAttempt.builder()
                        .id(id)
                        .order(order)
                        .amount(1000)
                        .expiresAt(Instant.now().plusSeconds(300))
                        .now(Instant.now())
                        .build();
        attempts.saveAndFlush(row);
        assertThat(check(order, id, true)).isEqualTo(1);
        row.status = Status.APPROVED;
        attempts.saveAndFlush(row);
        assertThat(check(order, id, true)).isZero();
        assertThat(check(UUID.randomUUID(), id, true)).isEqualTo(1);
        row.cancellationRequested = true;
        attempts.saveAndFlush(row);
        assertThat(check(order, id, true)).isEqualTo(1);
        row.status = Status.CANCELED;
        attempts.saveAndFlush(row);
        assertThat(check(order, id, false)).isZero();
        assertThat(check(order, UUID.randomUUID(), false)).isEqualTo(1);
    }

    @Test
    void liveLeaseIsNotTreatedAsFinished() {
        UUID id = UUID.randomUUID(), order = UUID.randomUUID();
        var row =
                PaymentAttempt.builder()
                        .id(id)
                        .order(order)
                        .amount(1000)
                        .expiresAt(Instant.now().plusSeconds(300))
                        .now(Instant.now())
                        .build();
        row.status = Status.APPROVED;
        row.leaseUntil = Instant.now().plusSeconds(60);
        attempts.saveAndFlush(row);
        assertThat(check(order, id, true)).isEqualTo(1);
    }

    long check(UUID order, UUID attempt, boolean approved) {
        return service.inspect(
                        new PaymentClearanceRequest(
                                List.of(
                                        new PaymentClearanceRequest.Reference(
                                                order, attempt, approved))))
                .pendingPayments();
    }
}
