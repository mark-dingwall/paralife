package com.paralife.admission;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AdmissionMetricsTest {

    @Test
    void rejectedCounterTaggedByReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = new AdmissionMetrics(registry);
        metrics.incRejected(RejectionToken.WORLD_FULL);
        metrics.incRejected(RejectionToken.WORLD_FULL);
        metrics.incRejected(RejectionToken.TICK_OVERLOAD);
        assertThat(registry.counter(AdmissionMetrics.M_REJECTED, "reason", RejectionToken.WORLD_FULL).count()).isEqualTo(2.0);
        assertThat(registry.counter(AdmissionMetrics.M_REJECTED, "reason", RejectionToken.TICK_OVERLOAD).count()).isEqualTo(1.0);
        assertThat(registry.counter(AdmissionMetrics.M_REJECTED, "reason", RejectionToken.MAINTENANCE).count()).isEqualTo(0.0);
    }

    @Test
    void ingressOverwriteCounterIsAggregate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = new AdmissionMetrics(registry);
        metrics.incIngressOverwrite();
        metrics.incIngressOverwrite();
        assertThat(registry.counter(AdmissionMetrics.M_INGRESS_OVERWRITES).count()).isEqualTo(2.0);
    }

    @Test
    void frameSizeDistributionSummaryRecords() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = new AdmissionMetrics(registry);
        metrics.recordFrameSize(128);
        metrics.recordFrameSize(256);
        var summary = registry.get(AdmissionMetrics.M_FRAME_SIZE).summary();
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.totalAmount()).isEqualTo(384.0);
    }

    @Test
    void activeEntitiesGaugeReflectsSetter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = new AdmissionMetrics(registry);
        metrics.setActiveEntities(42);
        assertThat(registry.get(AdmissionMetrics.M_ACTIVE_ENTITIES).gauge().value()).isEqualTo(42.0);
        metrics.setActiveEntities(7);
        assertThat(registry.get(AdmissionMetrics.M_ACTIVE_ENTITIES).gauge().value()).isEqualTo(7.0);
    }

    @Test
    void maintenanceGaugeMirrorsBooleanAs0Or1() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = new AdmissionMetrics(registry);
        metrics.setMaintenance(false);
        assertThat(registry.get(AdmissionMetrics.M_MAINTENANCE).gauge().value()).isEqualTo(0.0);
        metrics.setMaintenance(true);
        assertThat(registry.get(AdmissionMetrics.M_MAINTENANCE).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void tickWorkMsGaugeTracksSetter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = new AdmissionMetrics(registry);
        metrics.setLastTickWorkMs(123L);
        assertThat(registry.get(AdmissionMetrics.M_TICK_WORK_MS).gauge().value()).isEqualTo(123.0);
    }

    @Test
    void stalledSessionsGaugeTracksSetter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = new AdmissionMetrics(registry);
        metrics.setStalledSessions(3);
        assertThat(registry.get(AdmissionMetrics.M_STALLED_SESSIONS).gauge().value()).isEqualTo(3.0);
    }
}
