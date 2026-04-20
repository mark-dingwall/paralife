package com.paralife.websocket;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.TickEvent;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.world.Position;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Plan 15-10 Task 4: end-to-end meter wiring. Proves that
 * {@link SessionRegistry#register}/{@code unregister} and
 * {@link TickBroadcaster#onTick(TickEvent)} actually drive the meters, rather
 * than only bean-level priming (which would show reachability but not wiring).
 *
 * <p>Addresses cross-AI review consensus #6 (Claude + Codex MEDIUM): the bean
 * is exercised through its real call paths; no {@code metrics.recordFrameSize}
 * or {@code metrics.setActiveSessions} is called directly in this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WebSocketMetricsWiringTest {

    @Autowired SessionRegistry sessionRegistry;
    @Autowired TickBroadcaster broadcaster;
    @Autowired BotRegistry botRegistry;
    @Autowired WebSocketMetrics metrics;
    @Autowired MeterRegistry meterRegistry;

    @AfterEach
    void cleanupBots() {
        botRegistry.clear();
    }

    @Test
    void sessionRegisterUnregisterDrivesActiveSessionsGauge() {
        Gauge g = meterRegistry.find(WebSocketMetrics.M_ACTIVE_SESSIONS).gauge();
        assertNotNull(g, "active.sessions gauge should be registered");
        double before = g.value();

        WebSocketSession mock = mockSession("wiring-s1");
        sessionRegistry.register(mock);

        assertEquals(before + 1.0, g.value(), 0.0001,
                "Registering a session should bump the gauge by 1");

        sessionRegistry.unregister(mock.getId());
        assertEquals(before, g.value(), 0.0001,
                "Unregistering the session should return the gauge to its pre-test value");
    }

    @Test
    void broadcasterTickDrivesTickFrameBytesDistribution() {
        DistributionSummary ds = meterRegistry.find(WebSocketMetrics.M_TICK_FRAME_BYTES).summary();
        assertNotNull(ds, "tick.frame.bytes DistributionSummary should be registered");
        long countBefore = ds.count();

        // Drive a real tick. Register at least one bot so TickBroadcaster
        // iterates and reaches metrics.recordFrameSize. The mock session's
        // sendMessage is a no-op — the metric is still recorded right after.
        String sid = "wiring-s2";
        WebSocketSession mock = mockSession(sid);
        sessionRegistry.register(mock);
        // BotRegistry.register needs (sessionId, entityId, Position). The bot
        // doesn't need a live Particle in WorldGrid — buildTickFrame handles
        // null occupants by emitting a zero-energy alive-check frame.
        botRegistry.register(sid, "wiring-entity-1", new Position(1, 1));

        broadcaster.onTick(new TickEvent(1L));

        long countAfter = ds.count();
        assertTrue(countAfter > countBefore,
                "tick.frame.bytes count should have incremented (before="
                        + countBefore + " after=" + countAfter + ")");

        sessionRegistry.unregister(sid);
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession s = Mockito.mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }
}
