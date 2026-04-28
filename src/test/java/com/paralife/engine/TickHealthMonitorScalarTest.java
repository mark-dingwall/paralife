package com.paralife.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paralife.admission.AdmissionMetrics;
import com.paralife.admission.TickHealthMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Plan 18-02 Task 2 — locks the D-12 scalar invariant: TICK-HEALTH log lines must never
 * carry a {@code source=} field.
 *
 * <p><b>Round 2 Codex+OpenCode MEDIUM amendment — NON-VACUOUS drive:</b>
 * The test MUST drive the TickHealthMonitor into a state that emits at least one
 * {@code TICK-HEALTH} log line before asserting the scalar shape. If no TICK-HEALTH lines
 * are emitted, the test fails with a clear diagnostic — silently passing via vacuity is
 * explicitly rejected.
 *
 * <p><b>Drive strategy — real threshold breach via ReflectionTestUtils:</b>
 * {@link TickEngine#getLastTickWorkMs()} is a volatile field on the real TickEngine bean.
 * We inject a high value (500ms, well above the 80% × 100ms = 80ms high-water mark) via
 * {@link ReflectionTestUtils#setField} and then call {@link TickHealthMonitor#onTick}
 * directly for each sample in the rolling window. Once the window is full, the monitor
 * computes the mean and emits {@code TICK-HEALTH degraded}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.tick.interval-ms=100",
        "paralife.world.width=8",
        "paralife.world.height=8",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=false",
        "paralife.admission.tick-overload.high-water-pct=80",
        "paralife.admission.tick-overload.low-water-pct=50",
        "paralife.admission.tick-overload.window-ticks=5"
})
class TickHealthMonitorScalarTest {

    @Autowired
    private TickHealthMonitor monitor;

    @Autowired
    private TickEngine tickEngine;

    private ListAppender<ILoggingEvent> appender;
    private Logger tickHealthLogger;

    @BeforeEach
    void attachAppender() {
        tickHealthLogger = (Logger) LoggerFactory.getLogger(TickHealthMonitor.class);
        appender = new ListAppender<>();
        appender.start();
        tickHealthLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        tickHealthLogger.detachAppender(appender);
        appender.list.clear();
    }

    @Test
    void tickHealthLogLinesAreScalar_noSourceField() {
        // Drive REAL threshold breach by injecting a high lastTickWorkMs value into the
        // real TickEngine bean (the field TickHealthMonitor reads on every onTick call).
        // High-water: 80% × 100ms = 80ms. We inject 500ms — safely above it.
        // Window size = 5. Drive 10 samples to ensure window is full and mean is computed.
        ReflectionTestUtils.setField(tickEngine, "lastTickWorkMs", 500L);

        for (int i = 1; i <= 10; i++) {
            monitor.onTick(new TickEvent(i));
        }

        // ── Round 2 Codex+OpenCode MEDIUM: assert the test is NOT vacuous BEFORE
        // asserting the scalar shape. If TickHealthMonitor never emitted a TICK-HEALTH
        // line, the drive above is wrong and the test would silently pass — fail with a
        // clear diagnostic instead.
        long tickHealthLines = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("TICK-HEALTH"))
                .count();

        if (tickHealthLines == 0) {
            fail("TickHealthMonitor did not emit any TICK-HEALTH lines; drive logic in this "
                    + "test is wrong. Cannot lock scalar invariant via vacuous pass. "
                    + "Inspect TickHealthMonitor.java for the actual emission path and update "
                    + "the test drive accordingly.");
        }
        assertThat(tickHealthLines).isGreaterThan(0);   // explicit redundancy

        // Now lock D-12 scalar invariant: NO source= field on any TICK-HEALTH line.
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(m -> m.startsWith("TICK-HEALTH"))
                .allMatch(m -> !m.contains("source="),
                        "TICK-HEALTH log lines must stay scalar (D-12); no source= field permitted");
    }
}
