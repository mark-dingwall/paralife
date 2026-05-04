package com.paralife.admission;

import com.paralife.bot.BotClient;
import com.paralife.bot.BotClientOptions;
import com.paralife.bot.BotIdentity;
import com.paralife.bot.HeuristicBrain;
import com.paralife.engine.TickEngine;
import com.paralife.websocket.SessionRegistry;
import com.paralife.websocket.WorldWebSocketHandler;
import io.micrometer.core.instrument.Counter;
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
 *       belt-and-braces signature pin. Compile-time already catches direct drift; this tripwire
 *       only adds value if a back-compat overload absorbs the existing call site.</li>
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
     * <p>Belt-and-braces signature pin. Compile-time already catches direct drift on
     * the existing call site below; this reflection check only adds value if a
     * back-compat overload absorbs the original signature (e.g. someone adds
     * {@code markStalled(WebSocketSession)} alongside the {@code long}-taking variant
     * and the call site silently picks the new one). Harmless and zero cost otherwise.
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

        // F1 / F10 amendment: snapshot the rebound counter and the harness-bucket
        // stalled gauge BEFORE the STALLED pivot. Post-rebind we assert:
        //   - rebound counter incremented (proves tryRebind() succeeded vs falling
        //     through to a fresh Allow with a new entityId — see AdmissionGate Guard 4),
        //   - stalled-sessions{harness=test-attribution} returned to its pre-stall
        //     value (proves decStalledBucketByTags fired on the rebind path).
        double reboundBefore = readReboundCounter();
        double stalledHarnessBefore = readStalledHarnessGauge();

        // Find the server-side session that carries harness=test-attribution and
        // force the STALLED transition. The new ATTR_HARNESS attribute is set in
        // WorldWebSocketHandler.afterConnectionEstablished via AttributionTagger.
        WebSocketSession session = sessionRegistry.getActiveSessions().stream()
                .filter(s -> "test-attribution".equals(s.getAttributes().get(AttributionTagger.ATTR_HARNESS)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No session found with harness=test-attribution"));

        handler.markStalled(session, tickEngine.currentTick());

        // Phase 19.1 D-07 amendment: markStalled now calls
        // outboundSender.detachSession(session, SERVICE_RESTARTED) which closes the transport
        // FIRST. The OOB 408 frame sent afterward is best-effort — sendOutOfBand short-circuits
        // on isOpen()==false. The close itself is the reconnect signal: BotClient.onClose fires
        // and reconnects with the stored resumeToken (lines 525-530 in BotClient.java).
        // We do NOT assert getE408ReconnectRequiredCount() >= 1 here because the 408 may not
        // arrive; instead we assert the rebind cycle completes (below), which is the stronger
        // invariant. The 408 counter remains observable for informational purposes.
        // See: CLAUDE.md "markStalled close-then-best-effort-OOB (Phase 19.1, D-07)"

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

        // F1: rebound counter must have incremented. AdmissionGate increments
        // M_REBOUND only on tryRebind() success; a fresh-Allow fallthrough with a new
        // entityId would still pass the gauge/session-attribute checks above but would
        // NOT bump this counter. This is the strongest invariant lock for "an actual
        // rebind happened" vs "a brand-new registration that happened to carry the
        // same headers".
        double reboundAfter = readReboundCounter();
        assertThat(reboundAfter - reboundBefore)
                .as("paralife.backpressure.rebound counter must have incremented — "
                        + "proves tryRebind() succeeded rather than falling through to "
                        + "Allow.INSTANCE with a fresh entityId "
                        + "(before=" + reboundBefore + ", after=" + reboundAfter + ")")
                .isGreaterThanOrEqualTo(1.0);

        // F10: harness-bucket stalled gauge must have returned to its pre-stall level.
        // The stalled.sessions{source=harness, harness=test-attribution} gauge is
        // incremented when markStalled fires and decremented by decStalledBucketByTags
        // on successful rebind. Asserting it returned to the snapshot value locks
        // that decrement call site.
        double stalledHarnessAfter = readStalledHarnessGauge();
        assertThat(stalledHarnessAfter)
                .as("paralife.backpressure.stalled.sessions{harness=test-attribution} "
                        + "gauge must have returned to its pre-stall value after rebind — "
                        + "proves decStalledBucketByTags fired "
                        + "(before=" + stalledHarnessBefore + ", after=" + stalledHarnessAfter + ")")
                .isLessThanOrEqualTo(stalledHarnessBefore);

        bot.disconnect();
    }

    private double readUnknownGauge() {
        Gauge g = meterRegistry.find("paralife.admission.active.entities")
                .tags("source", "unknown")
                .gauge();
        return g == null ? 0.0 : g.value();
    }

    private double readReboundCounter() {
        Counter c = meterRegistry.find("paralife.backpressure.rebound").counter();
        return c == null ? 0.0 : c.count();
    }

    private double readStalledHarnessGauge() {
        Gauge g = meterRegistry.find("paralife.backpressure.stalled.sessions")
                .tags("source", "harness", "harness", "test-attribution")
                .gauge();
        return g == null ? 0.0 : g.value();
    }
}
