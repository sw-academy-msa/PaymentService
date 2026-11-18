package com.msa.payment.domain.payment.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.payment.domain.payment.dto.response.*;
import com.msa.payment.domain.payment.entity.PaymentAttempt;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.domain.payment.repository.PaymentAttemptRepository;
import com.msa.payment.domain.payment.service.PaymentResultOutboxService;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

/** 임대 만료 경계와 결과 변경 여부만 검증하며 실제 공급자를 호출하지 않습니다. */
class PaymentWorkStoreImplTest {
    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final PaymentResultOutboxService results = mock(PaymentResultOutboxService.class);
    private final PaymentWorkStoreImpl store =
            new PaymentWorkStoreImpl(attempts, Clock.fixed(now, ZoneOffset.UTC), results);

    private PaymentAttempt attempt() {
        var a =
                PaymentAttempt.builder()
                        .id(UUID.randomUUID())
                        .order(UUID.randomUUID())
                        .amount(6000)
                        .expiresAt(now.plusSeconds(300))
                        .now(now)
                        .build();
        a.status = Status.UNKNOWN;
        a.lastFailureCode = "PROVIDER_PENDING";
        a.leaseToken = UUID.randomUUID();
        a.leaseUntil = now.plusSeconds(1);
        when(attempts.lock(a.id)).thenReturn(Optional.of(a));
        return a;
    }

    private PaymentWork work(PaymentAttempt a) {
        return PaymentWork.builder().attemptId(a.id).leaseToken(a.leaseToken).build();
    }

    @Test
    void nullAndExpiredLeasesCannotWriteResultsEvenBeforeReclaim() {
        for (Instant until : new Instant[] {null, now, now.minusSeconds(1)}) {
            var a = attempt();
            a.leaseUntil = until;
            var token = a.leaseToken;
            store.complete(work(a), new ProviderReply(Status.APPROVED, now, null, null));
            assertThat(a.status).isEqualTo(Status.UNKNOWN);
            assertThat(a.leaseToken).isEqualTo(token);
            assertThat(a.lastFailureCode).isEqualTo("PROVIDER_PENDING");
        }
        verifyNoInteractions(results);
    }

    @Test
    void missingRecordOrWrongTokenCannotWrite() {
        var a = attempt();
        store.complete(
                PaymentWork.builder().attemptId(a.id).leaseToken(UUID.randomUUID()).build(),
                ProviderReply.unknown("OTHER"));
        store.complete(
                PaymentWork.builder().attemptId(UUID.randomUUID()).build(),
                ProviderReply.unknown("OTHER"));
        assertThat(a.status).isEqualTo(Status.UNKNOWN);
        verifyNoInteractions(results);
    }

    @Test
    void repeatedUnknownSchedulesRetryWithoutRecordingAnotherEvent() {
        var a = attempt();
        store.complete(work(a), ProviderReply.unknown("PROVIDER_PENDING"));
        assertThat(a.leaseToken).isNull();
        assertThat(a.leaseUntil).isNull();
        assertThat(a.nextAttemptAt).isAfter(now);
        verifyNoInteractions(results);
    }

    @Test
    void statusApprovalTimeAndFailureCodeChangesEachRecordAnEvent() {
        var statusChange = attempt();
        store.complete(work(statusChange), new ProviderReply(Status.APPROVED, now, null, null));
        verify(results).record(statusChange);

        var timeChange = attempt();
        // 상태가 같더라도 공급자 재확인으로 승인 시각을 보완하면 발행해야 합니다.
        store.complete(
                work(timeChange), new ProviderReply(Status.UNKNOWN, now, null, "PROVIDER_PENDING"));
        verify(results).record(timeChange);

        var failureChange = attempt();
        store.complete(work(failureChange), ProviderReply.unknown("PROVIDER_RESULT_UNKNOWN"));
        verify(results).record(failureChange);
        verifyNoMoreInteractions(results);
    }
}
