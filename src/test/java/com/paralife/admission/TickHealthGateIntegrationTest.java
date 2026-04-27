package com.paralife.admission;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paralife.engine.TickEngine;
import com.paralife.engine.TickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 Plan 11 — hysteresis admission-gate integration test.
 *
 * <p>Boots a real Spring context (no web), drives the {@link TickHealthMonitor} sample stream
 * by writing {@code lastTickWorkMs} on the live {@link TickEngine} bean and invoking
 * {@code onTick} directly, and asserts the {@link AdmissionGate} observes the gate-open boolean
 * via {@link AdmissionGate#evaluate}.
 *
 * <p>Watermarks: high=50% of 100ms = 50ms (strict), low=30% = 30ms (strict). Window=5.
 * Window-fill guard ensures one cold-start spike cannot trip overload (Plan 04 claude MEDIUM fix).
 *
 * <p>Log-marker assertions use substring matchers (claude LOW review fix) — fragile exact-string
 * regex would break on harmless message tweaks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.tick.interval-ms=100",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=false",
        "paralife.admission.cap=10",
        "paralife.admission.tick-overload.high-water-pct=50",
        "paralife.admission.tick-overload.low-water-pct=30",
        "paralife.admission.tick-overload.window-ticks=5"
})
@Tag("slow")
class TickHealthGateIntegrationTest {

    @Autowired TickEngine tickEngine;
    @Autowired TickHealthMonitor monitor;
    @Autowired AdmissionGate admissionGate;

    private ListAppender<ILoggingEvent> monitorAppender;

    @BeforeEach
    void attachAppender() {
        Logger monitorLogger = (Logger) LoggerFactory.getLogger(TickHealthMonitor.class);
        monitorAppender = new ListAppender<>();
        monitorAppender.setContext(monitorLogger.getLoggerContext());
        monitorAppender.start();
        monitorLogger.addAppender(monitorAppender);

        // Per-test isolation: the monitor bean is shared across @Test methods in the same Spring
        // context. Reset its rolling-window state so prior tests cannot influence this one.
        long[] window = (long[]) ReflectionTestUtils.getField(monitor, "window");
        if (window != null) {
            for (int i = 0; i < window.length; i++) window[i] = 0L;
        }
        ReflectionTestUtils.setField(monitor, "head", 0);
        ReflectionTestUtils.setField(monitor, "sum", 0L);
        ReflectionTestUtils.setField(monitor, "filled", 0);
        ReflectionTestUtils.setField(monitor, "overloaded", false);
    }

    @AfterEach
    void detachAppender() {
        Logger monitorLogger = (Logger) LoggerFactory.getLogger(TickHealthMonitor.class);
        monitorLogger.detachAppender(monitorAppender);
        monitorAppender.stop();
    }

    /** Set lastTickWorkMs on the real TickEngine, then drive one onTick on the monitor. */
    private void pushSample(long workMs, long tickNumber) {
        ReflectionTestUtils.setField(tickEngine, "lastTickWorkMs", workMs);
        monitor.onTick(new TickEvent(tickNumber));
    }

    private List<String> messages() {
        synchronized (monitorAppender.list) {
            List<ILoggingEvent> snap = new ArrayList<>(monitorAppender.list);
            return snap.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    private AdmissionGate.AdmissionRequest req(String sessionId, long tick) {
        return new AdmissionGate.AdmissionRequest(sessionId, tick, false, false, 0, Optional.empty());
    }

    @Test
    void warmupSpikeDoesNotTrip() {
        // Window-fill guard (Plan 04 claude MEDIUM): a single cold-start spike cannot trip overload.
        pushSample(1000, 1);
        assertThat(monitor.isOverloaded()).isFalse();
    }

    @Test
    void hysteresisGateOpensAndClosesWithLogMarkers() {
        // 5 high samples (80ms > 50ms watermark) — gate opens after window fills.
        for (int i = 1; i <= 5; i++) pushSample(80, i);
        assertThat(monitor.isOverloaded()).isTrue();
        assertThat(messages()).anyMatch(m -> m.contains("TICK-HEALTH degraded") && m.contains("high-water-pct="));

        // 5 low samples (20ms < 30ms watermark) — gate closes.
        for (int i = 6; i <= 10; i++) pushSample(20, i);
        assertThat(monitor.isOverloaded()).isFalse();
        assertThat(messages()).anyMatch(m -> m.contains("TICK-HEALTH recovered") && m.contains("low-water-pct="));
    }

    @Test
    void gateClosedWhenTickHealthOverloaded() {
        // Drive overload, then call admissionGate.evaluate — must reject with tick-overload token.
        for (int i = 1; i <= 5; i++) pushSample(80, i);
        assertThat(monitor.isOverloaded()).isTrue();

        AdmissionResult r = admissionGate.evaluate(req("session-A", 100));
        assertThat(r).isInstanceOf(AdmissionResult.Reject.class);
        AdmissionResult.Reject rej = (AdmissionResult.Reject) r;
        assertThat(rej.code()).isEqualTo(429);
        assertThat(rej.token()).isEqualTo(RejectionToken.TICK_OVERLOAD);
    }

    @Test
    void gateOpensAfterRecovery() {
        // Trip overload.
        for (int i = 1; i <= 5; i++) pushSample(80, i);
        assertThat(monitor.isOverloaded()).isTrue();
        // Recover with low samples.
        for (int i = 6; i <= 10; i++) pushSample(20, i);
        assertThat(monitor.isOverloaded()).isFalse();

        AdmissionResult r = admissionGate.evaluate(req("session-B", 200));
        assertThat(r).isInstanceOf(AdmissionResult.Allow.class);
    }

    @Test
    void inBandSamplesDoNotFlipState() {
        // Trip overload.
        for (int i = 1; i <= 5; i++) pushSample(80, i);
        assertThat(monitor.isOverloaded()).isTrue();

        // Push samples in the hysteresis band (40ms — between low=30 and high=50).
        // Mean stays > low watermark, so state must NOT flip back to non-overloaded.
        for (int i = 6; i <= 10; i++) pushSample(40, i);
        assertThat(monitor.isOverloaded()).isTrue();

        // No "TICK-HEALTH recovered" line should have fired during in-band samples.
        long recoveredCount = messages().stream()
                .filter(m -> m.contains("TICK-HEALTH recovered"))
                .count();
        assertThat(recoveredCount).isEqualTo(0L);
    }
}
