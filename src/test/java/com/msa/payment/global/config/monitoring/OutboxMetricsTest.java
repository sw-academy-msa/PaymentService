package com.msa.payment.global.config.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.msa.payment.domain.outbox.dto.response.OutboxMetricsSnapshot;
import com.msa.payment.domain.outbox.repository.OutboxEventRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

/** 수집 실패를 정상 0건으로 바꾸지 않는지와 DB 부하를 scrape에 연결하지 않는지 확인합니다. */
class OutboxMetricsTest {
    @Test
    void cachesSnapshotAndMarksDatabaseFailure() {
        var repository = mock(OutboxEventRepository.class);
        var snapshot = mock(OutboxMetricsSnapshot.class);
        when(snapshot.getPending()).thenReturn(3L);
        when(snapshot.getOldest()).thenReturn(java.time.Instant.now().minusSeconds(60));
        when(repository.monitoringSnapshot()).thenReturn(snapshot);
        var registry = new SimpleMeterRegistry();
        var metrics = new OutboxMetricsConfig(repository, registry);
        metrics.sample();
        assertThat(registry.get("stockdrop.outbox.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("stockdrop.outbox.oldest.pending.seconds").gauge().value())
                .isGreaterThanOrEqualTo(60);
        verify(repository, times(1)).monitoringSnapshot();
        when(repository.monitoringSnapshot()).thenThrow(new IllegalStateException());
        metrics.sample();
        assertThat(registry.get("stockdrop.outbox.collector.available").gauge().value()).isZero();
        assertThat(registry.get("stockdrop.outbox.pending").gauge().value()).isEqualTo(3);
    }
}
