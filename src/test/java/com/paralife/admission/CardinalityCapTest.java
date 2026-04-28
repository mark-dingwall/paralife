package com.paralife.admission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Tests for cardinality cap enforcement on the MeterFilter (D-10 defense-in-depth) and
 * overflow bucket accumulation in AdmissionMetrics.
 *
 * <p>Verifies:
 * - 64 distinct harness ids → 64 distinct gauges; 65th and 66th fold to harness=overflow
 * - 100 unique harness ids: sum(activeBucket values) == sum(all gauge values)
 * - Exactly ONE warn-once log line across all 65+ overflow events; raw 65th id in log
 */
class CardinalityCapTest {

    private SimpleMeterRegistry registry;
    private AdmissionMetrics metrics;
    private AttributionTagger tagger;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        // Use cap=64 matching default
        AdmissionConfig admissionConfig = AdmissionConfig.defaults();
        com.paralife.engine.TickEngine tickEngine = mock(com.paralife.engine.TickEngine.class);
        when(tickEngine.currentTick()).thenReturn(0L);
        tagger = new AttributionTagger(64, tickEngine);
        metrics = new AdmissionMetrics(registry, admissionConfig, tickEngine, tagger);
    }

    @Test
    void sixtyFourHarnessIdsProduceSixtyFourGauges() {
        for (int i = 1; i <= 64; i++) {
            FakeSession s = harnessSession("h-" + i, "entity-" + i);
            metrics.incActiveBucket(s);
        }
        // Each harness should have its own gauge with value 1
        for (int i = 1; i <= 64; i++) {
            Gauge gauge = registry.find(AdmissionMetrics.M_ACTIVE_ENTITIES)
                    .tags("source", "harness", "harness", "h-" + i)
                    .gauge();
            assertThat(gauge).as("gauge for h-" + i).isNotNull();
            assertThat(gauge.value()).as("value for h-" + i).isEqualTo(1.0);
        }
    }

    @Test
    void sixtyFifthAndSixtysSixthHarnessFoldToOverflow() {
        // Fill cap
        for (int i = 1; i <= 64; i++) {
            metrics.incActiveBucket(harnessSession("h-" + i, "entity-" + i));
        }
        // 65th and 66th should fold to overflow
        metrics.incActiveBucket(harnessSession("h-65", "entity-65"));
        metrics.incActiveBucket(harnessSession("h-66", "entity-66"));

        Gauge overflowGauge = registry.find(AdmissionMetrics.M_ACTIVE_ENTITIES)
                .tags("source", "harness", "harness", "overflow")
                .gauge();
        assertThat(overflowGauge).isNotNull();
        assertThat(overflowGauge.value()).isEqualTo(2.0);
    }

    @Test
    void mapRegistryAgreementFor100UniqueHarnesses() {
        // Register 100 unique harnesses; overflow folds at 65+
        for (int i = 1; i <= 100; i++) {
            FakeSession s = harnessSession("h-" + i, "entity-" + i);
            metrics.incActiveBucket(s);
        }

        // Sum all active entity gauges (all tags) in the registry
        double gaugeSum = registry.find(AdmissionMetrics.M_ACTIVE_ENTITIES)
                .gauges()
                .stream()
                .mapToDouble(Gauge::value)
                .sum();

        // Should equal 100 (1 for each registration)
        assertThat(gaugeSum).isEqualTo(100.0);
    }

    @Test
    void warnOnceLogContainsRaw65thId() {
        Logger logger = (Logger) LoggerFactory.getLogger(AttributionTagger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);

        try {
            for (int i = 1; i <= 64; i++) {
                metrics.incActiveBucket(harnessSession("h-" + i, "entity-" + i));
            }
            // 65th triggers warn-once
            metrics.incActiveBucket(harnessSession("h-65", "entity-65"));
            // 66th, 67th — must NOT emit additional warn lines
            metrics.incActiveBucket(harnessSession("h-66", "entity-66"));
            metrics.incActiveBucket(harnessSession("h-67", "entity-67"));

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getMessage().contains("HARNESS overflow first-seen"))
                    .toList();

            assertThat(warnings).hasSize(1);
            String msg = warnings.get(0).getFormattedMessage();
            assertThat(msg).contains("h-65");
            assertThat(msg).doesNotContain("=overflow");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    static FakeSession harnessSession(String harnessId, String entityId) {
        FakeSession s = new FakeSession();
        s.attrs().put(AttributionTagger.ATTR_SOURCE, "harness");
        s.attrs().put(AttributionTagger.ATTR_HARNESS, harnessId);
        if (entityId != null) {
            s.attrs().put(AdmissionMetrics.ATTR_ENTITY_ID, entityId);
        }
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
