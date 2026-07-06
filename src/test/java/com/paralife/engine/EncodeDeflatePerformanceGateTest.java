package com.paralife.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.paralife.bot.BotClient;
import com.paralife.bot.HeuristicBrain;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Performance gate for the encode+deflate path under target load.
 *
 * <p>With {@code permessage-deflate; server_no_context_takeover=true} negotiated
 * by every {@link BotClient} (see {@code BotClient.connect()} line ~111), every
 * outbound tick frame is compressed cold (no cross-frame dictionary reuse), and
 * every per-bot frame is a unique payload — so CPU cost is highest when this
 * combination runs at target scale. The gate asserts the encode+deflate path
 * does not starve the tick loop.
 *
 * <p><b>The assertion:</b> {@code TickEngine} publishes
 * {@code paralife.tick.work.ms} (a percentile {@link DistributionSummary},
 * registered unconditionally in the {@code TickEngine} constructor) every tick,
 * so the gate reads its p99 and asserts
 * {@code p99 < 2 × paralife.tick.interval-ms}. Higher drift = encode+deflate is
 * eating the tick budget; the gate catches that. The bound is relative to the
 * configured tick budget, not an absolute millisecond count, so it stays
 * meaningful when transplanted to a weaker machine — it fails honestly there iff
 * that box genuinely cannot host the sim within budget. The meter's presence is
 * asserted up front: it is always registered when {@code TickEngine} is in the
 * context, so its absence (or zero samples) is a setup/tick-loop failure the gate
 * must surface loudly — never silently downgrade to a weaker proxy. (An earlier
 * connection-survival fallback was scaffolding from when the meter did not yet
 * exist (plan 15-11); the follow-up that added it (2026-04-21) made the fallback
 * dead, and it was removed in Phase 22.1.)
 *
 * <p><b>Scale:</b> The plan envelope targets 100 bots × 500 ticks. At
 * {@code interval-ms=200}, 500 ticks = 100s wallclock, which is too slow for
 * routine CI. This test runs the same 100-bot setup but shortens the sampling
 * window to {@link #TARGET_TICKS} ticks (~{@code TARGET_TICKS × interval-ms}
 * wallclock). CI can scale up by raising {@link #TARGET_TICKS} — the assertion
 * is p99 drift relative to target, not absolute wallclock, so the bound stays
 * meaningful at either scale.
 *
 * <p>Per review Codex #c (MEDIUM). Complements correctness tests in 15-02 /
 * 15-05 / 15-08.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=true",
        "paralife.tick.interval-ms=200",
        "paralife.world.width=64",
        "paralife.world.height=64",
        "paralife.world.rock.seed=42",
        "paralife.simulation.energy-decay-per-tick=0",
        "paralife.simulation.overcrowding-energy-penalty=0",
        "management.endpoints.web.exposure.include=health,info,metrics"
})
// @Tag("slow"): excluded from the default `./gradlew test` gate (build.gradle.kts) and run
// only under -PincludeLong=true. REQUIRED — the p99 tick-work assertion is a live statistical
// aggregate; the firewall (CLAUDE.md) bars such asserts from the default suite. "performance"
// is NOT excluded by build.gradle.kts, so it must be "slow" here.
@Tag("slow")
class EncodeDeflatePerformanceGateTest {

    private static final Logger log =
            LoggerFactory.getLogger(EncodeDeflatePerformanceGateTest.class);

    /** Target population — matches the plan's 100-bot envelope. */
    private static final int BOT_COUNT = 100;

    /**
     * Sampling window in ticks. Reduced from the plan's 500-tick envelope so
     * routine CI runs finish in ~10s wallclock at {@code interval-ms=200}. The
     * survival / p99-drift assertion is scale-invariant; CI can raise this to
     * 500 when closing on a perf-regression review.
     */
    private static final int TARGET_TICKS = 50;

    @LocalServerPort int port;
    @Autowired MeterRegistry meterRegistry;
    @Value("${paralife.tick.interval-ms}")
    long intervalMillis;

    private final List<BotClient> bots = new ArrayList<>(BOT_COUNT);

    @AfterEach
    void tearDown() {
        for (BotClient b : bots) {
            try {
                b.disconnect();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        bots.clear();
    }

    @Test
    void encodeDeflateUnder100BotsTickDrift() throws Exception {
        String uri = "ws://localhost:" + port + "/ws/world";

        // Connect BOT_COUNT bots concurrently on virtual threads — mirrors
        // LoadTest / BotLauncher's established pattern. Concurrent connect
        // puts the encode+deflate path under realistic handshake pressure.
        List<Thread> connectThreads = new ArrayList<>(BOT_COUNT);
        for (int i = 0; i < BOT_COUNT; i++) {
            char species = (i % 3 == 0) ? 'C' : (i % 3 == 1 ? 'M' : 'S');
            BotClient bot = new BotClient(uri, species,
                    new HeuristicBrain(HeuristicBrain.REPRODUCE_THRESHOLD),
                    200L, 100L);
            bots.add(bot);
            connectThreads.add(Thread.startVirtualThread(() -> {
                try {
                    bot.connect();
                } catch (Exception e) {
                    log.warn("Bot connect failed: {}", e.getMessage());
                }
            }));
        }
        for (Thread t : connectThreads) t.join(10_000);

        int connected = 0;
        int registered = 0;
        for (BotClient b : bots) {
            if (b.awaitConnected(5_000)) connected++;
            if (b.awaitRegistered(5_000)) registered++;
        }
        log.info("Bots connected={}/{} registered={}/{}",
                connected, BOT_COUNT, registered, BOT_COUNT);

        // Lenient registration floor — some bots may fail to register under
        // concurrent-connect pressure, but the encode+deflate path is already
        // exercised by the ones that did. Keep consistent with LoadTest's
        // 80% floor; the post-run p99 tick-drift check is the actual gate.
        assertTrue(registered >= (int) (BOT_COUNT * 0.8),
                "Expected at least 80% of bots registered; got "
                        + registered + "/" + BOT_COUNT);

        // Sampling window. interval-ms × TARGET_TICKS = wallclock.
        long runMillis = intervalMillis * TARGET_TICKS;
        log.info("Sampling for {} ticks × {}ms = {}ms wallclock",
                TARGET_TICKS, intervalMillis, runMillis);
        Thread.sleep(runMillis);

        // The gate: p99 tick-work drift. The meter is registered unconditionally
        // in the TickEngine constructor, so its absence — or zero samples after a
        // full sampling window — means the tick loop never ran, which the gate
        // must fail loudly rather than paper over. (No survival-proxy fallback:
        // that was scaffolding from before the meter existed; see class javadoc.)
        DistributionSummary drift =
                meterRegistry.find("paralife.tick.work.ms").summary();
        assertNotNull(drift,
                "paralife.tick.work.ms meter absent — TickEngine registers it in its"
                        + " constructor, so absence means the tick engine is not wired up.");
        assertTrue(drift.count() > 0,
                "No tick-work samples recorded after " + runMillis
                        + "ms — the tick loop produced zero ticks (starved or not started).");

        double p99 = drift.percentile(0.99);
        double budget = 2.0 * intervalMillis;
        log.info("Drift metric: p99={}ms budget={}ms samples={}", p99, budget, drift.count());
        assertTrue(p99 < budget,
                "Tick drift p99=" + p99 + "ms exceeded 2× target ("
                        + budget + "ms). Encode+deflate path may be over budget.");
    }
}
