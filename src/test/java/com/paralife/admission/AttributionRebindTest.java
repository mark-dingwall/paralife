package com.paralife.admission;

import com.paralife.bot.BotClient;
import com.paralife.bot.BotClientOptions;
import com.paralife.bot.BotIdentity;
import com.paralife.bot.HeuristicBrain;
import com.paralife.engine.TickEngine;
import com.paralife.websocket.SessionRegistry;
import com.paralife.websocket.WorldWebSocketHandler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test locking T-18-04: STALLED-pivot rebind preserves source/harness attribution.
 *
 * <p>Exercises the full STALLED → E|408 → reconnect → rebind cycle using the same BotClient
 * instance's internal reconnect loop (NOT a fresh constructor). This addresses Codex's Plan 01
 * MEDIUM concern about constructor-only test coverage (Round 2 LOW carry-over): the rebind path
 * is end-to-end exercised through the actual reconnect loop here.
 *
 * <h2>Round 2 amendments applied</h2>
 * <ul>
 *   <li><b>OpenCode MEDIUM (pre-flight signature check):</b> {@link #verifyMarkStalledSignature()}
 *       uses reflection to assert {@code markStalled(WebSocketSession, long)} exists before any
 *       test logic runs. Fails fast on signature drift rather than at compile time.</li>
 *   <li><b>Codex MEDIUM (before/after gauge comparison):</b> the unknown-source gauge is
 *       snapshotted BEFORE the STALLED pivot; after rebind we assert it has NOT grown.
 *       Avoids brittleness against shared-registry state from prior tests.</li>
 *   <li><b>Awaitility budget: 5 seconds</b> for CI stability (Round 1 amendment preserved).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "paralife.admission.cap=8",
        "paralife.admission.backpressure.outbound-queue-size=4",
        "paralife.admission.backpressure.grace-window-ticks=50"
})
class AttributionRebindTest {

    @LocalServerPort
    int port;

    @Autowired
    WorldWebSocketHandler handler;

    @Autowired
    SessionRegistry sessionRegistry;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    TickEngine tickEngine;

    /**
     * Round 2 OpenCode MEDIUM amendment: pre-flight signature check via reflection.
     *
     * <p>Asserts that {@code WorldWebSocketHandler.markStalled(WebSocketSession, long)}
     * exists with exactly the expected parameter types. If the signature drifted during
     * plan execution, this fails fast here — before the test reaches the actual call site —
     * giving a clear error rather than an obscure compile failure or NPE.
     *
     * @throws NoSuchMethodException if the signature doesn't match expectations
     */
    @BeforeAll
    static void verifyMarkStalledSignature() throws NoSuchMethodException {
        WorldWebSocketHandler.class.getMethod("markStalled", WebSocketSession.class, long.class);
        // If we reach here without NoSuchMethodException, signature is exactly:
        // public void markStalled(WebSocketSession session, long stallTick)
    }

    @Test
    void stalledPivotPreservesSourceAndHarnessAttribution() throws Exception {
        String uri = "ws://localhost:" + port + "/ws/world";

        // Use BotIdentity.harness("test-attribution") so the server-side
        // AttributionTagger picks up source=harness, harness=test-attribution.
        BotClient bot = new BotClient(new BotClientOptions(
                uri, 'C',
                new HeuristicBrain(HeuristicBrain.REPRODUCE_THRESHOLD),
                100L, 50L, new Random(),
                BotIdentity.harness("test-attribution")));

        bot.connect();
        assertThat(bot.awaitRegistered(5_000L))
                .as("Bot must register within 5s")
                .isTrue();

        // Round 2 Codex MEDIUM amendment: snapshot the unknown-source gauge BEFORE the
        // rebind. The negative assertion later compares before/after rather than asserting
        // an absolute value < 1.0, which would be brittle against shared-registry state
        // from prior tests polluting the shared MeterRegistry.
        double unknownBefore = readUnknownGauge();

        // Find the server-side session that carries harness=test-attribution and
        // force the STALLED transition. The new ATTR_HARNESS attribute is set in
        // WorldWebSocketHandler.afterConnectionEstablished via AttributionTagger.
        WebSocketSession session = sessionRegistry.getActiveSessions().stream()
                .filter(s -> "test-attribution".equals(s.getAttributes().get(AttributionTagger.ATTR_HARNESS)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No session found with harness=test-attribution"));

        handler.markStalled(session, tickEngine.currentTick());

        // The bot should receive E|408|reconnect-required and increment its counter.
        // Allow 5 seconds for CI reliability.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() ->
                        assertThat(bot.getE408ReconnectRequiredCount())
                                .as("Bot must have received E|408|reconnect-required")
                                .isGreaterThanOrEqualTo(1));

        // Wait for the STALLED-pivot reconnect to complete: a new server-side session must
        // exist with both ATTR_HARNESS=test-attribution AND ATTR_SOURCE=harness, and must
        // have an entityId (meaning the rebind succeeded and registration completed).
        //
        // Note: this is the same BotClient instance's internal reconnect loop
        // (BotClient.handleStalled → connect() → sendInitialRegister) — NOT a fresh
        // constructor. This covers the end-to-end rebind path including header re-emission.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    long n = sessionRegistry.getActiveSessions().stream()
                            .filter(s -> "test-attribution".equals(
                                    s.getAttributes().get(AttributionTagger.ATTR_HARNESS)))
                            .filter(s -> "harness".equals(
                                    s.getAttributes().get(AttributionTagger.ATTR_SOURCE)))
                            .filter(s -> s.getAttributes().containsKey("entityId"))
                            .count();
                    assertThat(n)
                            .as("After rebind: at least one active session must have "
                                    + "harness=test-attribution, source=harness, and an entityId")
                            .isGreaterThanOrEqualTo(1);
                });

        // Positive gauge assertion: the harness-attribution bucket must show >= 1.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    Gauge g = meterRegistry.find("paralife.admission.active.entities")
                            .tags("source", "harness", "harness", "test-attribution")
                            .gauge();
                    assertThat(g)
                            .as("paralife.admission.active.entities{source=harness, harness=test-attribution} "
                                    + "gauge must exist")
                            .isNotNull();
                    assertThat(g.value())
                            .as("Active entities gauge for harness=test-attribution must be >= 1 after rebind")
                            .isGreaterThanOrEqualTo(1.0);
                });

        // Round 2 Codex MEDIUM amendment: NEGATIVE assertion via before/after comparison.
        // This proves the rebound bot did NOT degrade to source=unknown, regardless of
        // what other tests left in the shared registry before this test ran.
        double unknownAfter = readUnknownGauge();
        assertThat(unknownAfter)
                .as("Rebound bot must NOT degrade to source=unknown — "
                        + "the unknown-source gauge must not have grown "
                        + "(before=" + unknownBefore + ", after=" + unknownAfter + ")")
                .isLessThanOrEqualTo(unknownBefore);

        bot.disconnect();
    }

    private double readUnknownGauge() {
        Gauge g = meterRegistry.find("paralife.admission.active.entities")
                .tags("source", "unknown")
                .gauge();
        return g == null ? 0.0 : g.value();
    }
}
