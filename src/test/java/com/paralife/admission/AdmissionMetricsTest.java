package com.paralife.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AdmissionMetrics} — updated for the Phase 18 four-arg constructor
 * (Round 2 OpenCode HIGH amendment) and per-bucket API.
 *
 * <p>Tests for the new two-tag emission and lifecycle invariants live in
 * {@code AttributionTagTest}, {@code CardinalityCapTest}, and {@code AdmissionMetricsLifecycleTest}.
 * This file verifies the scalar invariants and back-compat shims.
 */
class AdmissionMetricsTest {

    private static AdmissionMetrics makeMetrics(SimpleMeterRegistry registry) {
        AdmissionConfig admissionConfig = AdmissionConfig.defaults();
        com.paralife.engine.TickEngine tickEngine = mock(com.paralife.engine.TickEngine.class);
        when(tickEngine.currentTick()).thenReturn(0L);
        AttributionTagger tagger = new AttributionTagger(64, tickEngine);
        return new AdmissionMetrics(registry, admissionConfig, tickEngine, tagger);
    }

    @Test
    void rejectedCounterTaggedByReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = makeMetrics(registry);
        // Use back-compat single-arg shim — maps to source=unknown
        metrics.incRejected(RejectionToken.WORLD_FULL);
        metrics.incRejected(RejectionToken.WORLD_FULL);
        metrics.incRejected(RejectionToken.TICK_OVERLOAD);
        assertThat(registry.counter(AdmissionMetrics.M_REJECTED,
                "reason", RejectionToken.WORLD_FULL, "source", "unknown").count())
                .isEqualTo(2.0);
        assertThat(registry.counter(AdmissionMetrics.M_REJECTED,
                "reason", RejectionToken.TICK_OVERLOAD, "source", "unknown").count())
                .isEqualTo(1.0);
        assertThat(registry.counter(AdmissionMetrics.M_REJECTED,
                "reason", RejectionToken.MAINTENANCE, "source", "unknown").count())
                .isEqualTo(0.0);
    }

    @Test
    void ingressOverwriteCounterIsAggregate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = makeMetrics(registry);
        // back-compat no-arg shim → source=unknown
        metrics.incIngressOverwrite();
        metrics.incIngressOverwrite();
        assertThat(registry.counter(AdmissionMetrics.M_INGRESS_OVERWRITES, "source", "unknown").count())
                .isEqualTo(2.0);
    }

    @Test
    void frameSizeDistributionSummaryRecords() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = makeMetrics(registry);
        metrics.recordFrameSize(128);
        metrics.recordFrameSize(256);
        var summary = registry.get(AdmissionMetrics.M_FRAME_SIZE).summary();
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.totalAmount()).isEqualTo(384.0);
    }

    @Test
    void maintenanceGaugeMirrorsBooleanAs0Or1() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = makeMetrics(registry);
        metrics.setMaintenance(false);
        assertThat(registry.get(AdmissionMetrics.M_MAINTENANCE).gauge().value()).isEqualTo(0.0);
        metrics.setMaintenance(true);
        assertThat(registry.get(AdmissionMetrics.M_MAINTENANCE).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void tickWorkMsGaugeTracksSetter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = makeMetrics(registry);
        metrics.setLastTickWorkMs(123L);
        assertThat(registry.get(AdmissionMetrics.M_TICK_WORK_MS).gauge().value()).isEqualTo(123.0);
    }

    @Test
    void setActiveEntitiesIsNoOpBackCompat() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = makeMetrics(registry);
        // Deprecated no-op — should not throw
        metrics.setActiveEntities(42);
    }

    @Test
    void setStalledSessionsIsNoOpBackCompat() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionMetrics metrics = makeMetrics(registry);
        // Deprecated no-op — should not throw
        metrics.setStalledSessions(3);
    }
}
