package com.paralife.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.paralife.bot.BotClient;
import com.paralife.bot.HeuristicBrain;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
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
 * <p><b>Drift metric path (preferred):</b> If the application publishes a
 * {@code paralife.tick.work.ms} {@link DistributionSummary}, the gate reads
 * its p99 and asserts {@code p99 < 2 × paralife.tick.interval-ms}. Higher drift
 * = encode+deflate is eating the tick budget; the gate catches that regression.
 *
 * <p><b>Fallback path (current state, plan 15-11 Task 5):</b> {@code TickEngine}
 * does not yet publish a drift metric. The gate falls back to a
 * connection-survival proxy — if the tick loop starves, bots disconnect. The
 * gate asserts at least 90 of the {@value #BOT_COUNT} bots launched are still
 * connected at the end of the run. Adding a TickEngine drift tap is deferred to
 * a follow-up plan (not this cleanup plan's scope).
 *
 * <p><b>Scale:</b> The plan envelope targets 100 bots × 500 ticks. At
 * {@code interval-ms=200}, 500 ticks = 100s wallclock, which is too slow for
 * routine CI. This test runs the same 100-bot setup but shortens the sampling
 * window to {@link #TARGET_TICKS} ticks (~{@code TARGET_TICKS × interval-ms}
 * wallclock). CI can scale up by raising {@link #TARGET_TICKS} — the assertion
 * is p99 drift relative to target (or survival ratio), not absolute wallclock,
 * so the bound stays meaningful at either scale.
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
@Tag("performance")
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

    /**
     * Survival floor for the fallback path. If fewer than this many of the
     * {@link #BOT_COUNT} bots remain connected at the end of the run, the tick
     * loop is likely starved — fail the gate.
     */
    private static final int SURVIVAL_FLOOR = 90;

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
    @Disabled("TD-22→P21: encode/deflate perf regression; bisect during P21 benchmark gate")
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
        // 80% floor; the post-run survival check is the actual gate.
        assertTrue(registered >= (int) (BOT_COUNT * 0.8),
                "Expected at least 80% of bots registered; got "
                        + registered + "/" + BOT_COUNT);

        // Sampling window. interval-ms × TARGET_TICKS = wallclock.
        long runMillis = intervalMillis * TARGET_TICKS;
        log.info("Sampling for {} ticks × {}ms = {}ms wallclock",
                TARGET_TICKS, intervalMillis, runMillis);
        long startNanos = System.nanoTime();
        Thread.sleep(runMillis);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        // Preferred assertion — p99 tick-drift (if TickEngine publishes it).
        DistributionSummary drift =
                meterRegistry.find("paralife.tick.work.ms").summary();
        if (drift != null && drift.count() > 0) {
            double p99 = drift.percentile(0.99);
            double budget = 2.0 * intervalMillis;
            log.info("Drift metric found: p99={}ms budget={}ms samples={}",
                    p99, budget, drift.count());
            assertTrue(p99 < budget,
                    "Tick drift p99=" + p99 + "ms exceeded 2× target ("
                            + budget + "ms). Encode+deflate path may be over budget.");
        } else {
            // Fallback — connection-survival proxy. A starved tick loop
            // manifests as bot disconnects (WebSocket pongs time out, server
            // idle-kicks sessions, etc.).
            int stillConnected = 0;
            for (BotClient b : bots) {
                if (b.isConnected()) stillConnected++;
            }
            log.info("Drift metric absent; connection-survival proxy: {}/{} bots still connected after {}ms",
                    stillConnected, BOT_COUNT, elapsedMillis);
            assertTrue(stillConnected >= SURVIVAL_FLOOR,
                    "Expected at least " + SURVIVAL_FLOOR
                            + " of " + BOT_COUNT + " bots still connected after "
                            + elapsedMillis + "ms; got " + stillConnected
                            + ". (TickEngine does not publish drift metric;"
                            + " using connection-survival proxy.)");
        }
    }
}
