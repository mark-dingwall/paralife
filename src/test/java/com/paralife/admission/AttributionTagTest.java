package com.paralife.admission;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for two-tag emission on {@link AdmissionMetrics} — D-12 schema verification.
 *
 * <p>Verifies:
 * - Rejecting a harness session emits counter tagged {reason, source=harness, harness=harness-A}
 * - Rejecting an unknown session emits counter tagged {reason, source=unknown} (no harness tag)
 * - paralife.admission.maintenance and paralife.tick.health.work-time-ms gauges are scalar (D-12)
 */
class AttributionTagTest {

    private SimpleMeterRegistry registry;
    private AdmissionMetrics metrics;
    private AttributionTagger tagger;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        AdmissionConfig admissionConfig = AdmissionConfig.defaults();
        com.paralife.engine.TickEngine tickEngine = mock(com.paralife.engine.TickEngine.class);
        when(tickEngine.currentTick()).thenReturn(0L);
        tagger = new AttributionTagger(64, tickEngine);
        metrics = new AdmissionMetrics(registry, admissionConfig, tickEngine, tagger);
    }

    // ── Rejected counter two-tag emission ────────────────────────────────────

    @Test
    void rejectedFromHarnessSessionTaggedWithSourceAndHarness() {
        FakeSession session = harnessSession("harness-A");
        metrics.incRejected(RejectionToken.WORLD_FULL, session);

        double count = registry.counter(
                AdmissionMetrics.M_REJECTED,
                "reason", RejectionToken.WORLD_FULL,
                "source", "harness",
                "harness", "harness-A"
        ).count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void rejectedFromUnknownSessionTaggedWithSourceOnly() {
        FakeSession session = new FakeSession();
        // No ATTR_SOURCE set → source=unknown, no harness tag
        metrics.incRejected(RejectionToken.TICK_OVERLOAD, session);

        double count = registry.counter(
                AdmissionMetrics.M_REJECTED,
                "reason", RejectionToken.TICK_OVERLOAD,
                "source", "unknown"
        ).count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void backCompatIncRejectedNullSessionUsesUnknown() {
        // single-arg back-compat shim → incRejected(reason, null)
        metrics.incRejected(RejectionToken.MAINTENANCE);
        double count = registry.counter(
                AdmissionMetrics.M_REJECTED,
                "reason", RejectionToken.MAINTENANCE,
                "source", "unknown"
        ).count();
        assertThat(count).isEqualTo(1.0);
    }

    // ── D-12 scalar invariant for maintenance + tick-work-ms ─────────────────

    @Test
    void maintenanceGaugeIsScalarNoSourceTag() {
        // M_MAINTENANCE should exist as a plain gauge with no source/harness tags
        metrics.setMaintenance(true);
        double val = registry.get(AdmissionMetrics.M_MAINTENANCE).gauge().value();
        assertThat(val).isEqualTo(1.0);

        // Verify no gauge for maintenance with source tag exists
        assertThat(registry.find(AdmissionMetrics.M_MAINTENANCE)
                .tags("source", "harness").gauge())
                .isNull();
    }

    @Test
    void tickWorkMsGaugeIsScalarNoSourceTag() {
        metrics.setLastTickWorkMs(99L);
        double val = registry.get(AdmissionMetrics.M_TICK_WORK_MS).gauge().value();
        assertThat(val).isEqualTo(99.0);

        assertThat(registry.find(AdmissionMetrics.M_TICK_WORK_MS)
                .tags("source", "harness").gauge())
                .isNull();
    }

    // ── Active entities gauge has source tag ─────────────────────────────────

    @Test
    void activeBucketGaugeHasSourceTag() {
        FakeSession session = harnessSession("h-1");
        session.attrs().put(AdmissionMetrics.ATTR_ENTITY_ID, "entity-1");
        metrics.incActiveBucket(session);

        Gauge gauge = registry.find(AdmissionMetrics.M_ACTIVE_ENTITIES)
                .tags("source", "harness", "harness", "h-1")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    void stalledBucketGaugeHasSourceTag() {
        FakeSession session = harnessSession("h-1");
        metrics.incStalledBucket(session, "entity-1");

        Gauge gauge = registry.find(AdmissionMetrics.M_STALLED_SESSIONS)
                .tags("source", "harness", "harness", "h-1")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    static FakeSession harnessSession(String harnessId) {
        FakeSession s = new FakeSession();
        s.attrs().put(AttributionTagger.ATTR_SOURCE, "harness");
        s.attrs().put(AttributionTagger.ATTR_HARNESS, harnessId);
        return s;
    }

    static class FakeSession implements WebSocketSession {
        private final Map<String, Object> attrs = new HashMap<>();

        Map<String, Object> attrs() { return attrs; }

        @Override public String getId() { return "test-" + System.identityHashCode(this); }
        @Override public URI getUri() { return null; }
        @Override public org.springframework.http.HttpHeaders getHandshakeHeaders() { return null; }
        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int maximumMessageSize) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int maximumMessageSize) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public void sendMessage(WebSocketMessage<?> message) {}
        @Override public boolean isOpen() { return true; }
        @Override public void close() {}
        @Override public void close(CloseStatus status) {}
    }
}
