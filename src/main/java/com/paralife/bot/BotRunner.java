package com.paralife.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Single-process operator CLI for launching bot clients against a live Paralife server.
 *
 * <h2>Scope</h2>
 * Minimum-viable operator CLI — glue layer over {@link BotFleet} that adds a
 * {@code main} entry point, SIGTERM/SIGINT shutdown handling, and an optional
 * duration timer for scripted UAT runs. All bot-side logic (connection,
 * registration, codec I/O, respawn FSM, heuristic brain) lives in
 * {@link BotClient} / {@link BotFleet} / {@link HeuristicBrain} and is
 * reused verbatim.
 *
 * <h2>Hard cap: 100 bots per invocation</h2>
 * Rejects {@code count > 100} with a non-zero exit. The v1.0/v2.0 milestone
 * success criteria (see {@code .planning/PROJECT.md}) validate up to 100
 * concurrent bots against a single-JVM server; beyond that is unvalidated
 * territory. The cap is enforced at the CLI boundary to force the scale
 * conversation to happen at the M4 boundary rather than silently drift.
 *
 * <h2>Explicit non-goals</h2>
 * <ul>
 *   <li>Multi-process coordination / harness-ID protocol</li>
 *   <li>Per-harness metrics</li>
 *   <li>Cross-process respawn semantics</li>
 *   <li>World-partition-aware bot placement</li>
 *   <li>Bot counts beyond 100</li>
 * </ul>
 * All of the above are M4 scope — the primitive that supersedes this CLI for
 * high-scale scenarios is the external load harness ({@code LoadHarness}, Plan 05).
 * Do not extend {@code BotRunner} in those directions; use the harness instead.
 *
 * <h2>Usage</h2>
 * <pre>
 *   ./gradlew runBot --args="ws://localhost:8080/ws/world 1"
 *   ./gradlew runBot --args="ws://localhost:8080/ws/world 100 60"
 * </pre>
 *
 * Arguments:
 * <ul>
 *   <li>{@code server-uri} — WebSocket endpoint, e.g. {@code ws://localhost:8080/ws/world}</li>
 *   <li>{@code count} — bot count, {@code 1 <= count <= 100}</li>
 *   <li>{@code duration-seconds} — optional; if omitted, runs until SIGINT/SIGTERM</li>
 * </ul>
 *
 * Exit codes: 0 on clean shutdown, 1 on arg error, 2 on launch failure.
 */
public final class BotRunner {

    private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

    static final int MAX_BOTS = 100;

    private BotRunner() {
        // CLI entry point only.
    }

    /**
     * Extracted entry point for testability (Round 2 Codex HIGH amendment).
     *
     * <p>The test seam accepts a fleet supplier + factory-supplier so
     * {@code BotRunnerOperatorTagTest} can assert that {@code BotRunner} itself
     * (not just {@link BotFleet} directly) launches with {@link BotIdentity#operator()}.
     * Previously the test launched {@link BotFleet} directly — it didn't actually prove
     * BotRunner passed the correct identity.
     *
     * @param args              CLI args as if from {@link #main(String[])}
     * @param fleetFactory      usually {@code BotFleet::new}; tests may inject a recording double
     * @param botFactoryFactory usually {@code BotFactory::new}; tests may inject a recording double
     * @return process exit code: 0 clean, 1 arg error, 2 launch failure
     */
    public static int run(String[] args,
                          Supplier<BotFleet> fleetFactory,
                          Function<String, BotFactory> botFactoryFactory) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: BotRunner <server-uri> <count> [duration-seconds]");
            System.err.println("  server-uri       e.g. ws://localhost:8080/ws/world");
            System.err.println("  count            1..100 (validated v1.0/v2.0 envelope)");
            System.err.println("  duration-seconds optional; omit for run-until-interrupted");
            return 1;
        }

        String uri = args[0];
        int count;
        try {
            count = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("count must be an integer (got '" + args[1] + "')");
            return 1;
        }

        if (count < 1) {
            System.err.println("count must be >= 1 (got " + count + ")");
            return 1;
        }
        if (count > MAX_BOTS) {
            System.err.println("count=" + count + " exceeds validated envelope (max="
                    + MAX_BOTS + "). The v1.0/v2.0 milestone success criteria validate "
                    + "up to " + MAX_BOTS + " concurrent bots per single-JVM process. "
                    + "For 1000+ scale use the M4 external load harness, not BotRunner.");
            return 1;
        }

        Long durationSeconds = null;
        if (args.length == 3) {
            try {
                durationSeconds = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("duration-seconds must be an integer (got '" + args[2] + "')");
                return 1;
            }
            if (durationSeconds < 1) {
                System.err.println("duration-seconds must be >= 1 (got " + durationSeconds + ")");
                return 1;
            }
        }

        log.info("BotRunner starting — uri={} count={} duration={}",
                uri, count, durationSeconds == null ? "indefinite" : durationSeconds + "s");

        BotFleet fleet = fleetFactory.get();
        BotFactory factory = botFactoryFactory.apply(uri);

        // Phase 18 D-09: BotRunner explicitly sets source=operator; no harness header.
        // This keeps "unknown" semantically distinct from the supported ≤100 operator path.
        BotIdentity identity = BotIdentity.operator();

        // Idempotent shutdown (Round 2 Claude MEDIUM — BotFleet.shutdown() is CAS-guarded).
        // Safe to call from both the shutdown hook and the main run() path.
        Thread shutdownHook = new Thread(() -> {
            log.info("BotRunner shutting down — draining {} bots", fleet.getBots().size());
            try {
                fleet.shutdown();
            } catch (RuntimeException e) {
                log.warn("shutdown raised: {}", e.getMessage());
            }
        }, "bot-runner-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            fleet.launch(uri, count, identity, RampUpSpec.instant(), SpeciesMix.balanced(), factory);
            try {
                fleet.awaitAllSettled().get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.info("BotRunner: not all bots settled within 30s (peak={}, current={})",
                        fleet.peakRegistered(), fleet.currentRegistered());
            }

            log.info("BotRunner: {} bots launched; {} remain after connect window",
                    count, fleet.getBots().size());

            final Long finalDurationSeconds = durationSeconds;
            if (finalDurationSeconds != null) {
                try {
                    Thread.sleep(finalDurationSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("BotRunner interrupted during duration sleep");
                }
                log.info("BotRunner duration reached; exiting");
            } else {
                log.info("BotRunner running indefinitely; Ctrl-C (SIGINT) or SIGTERM to stop");
                try {
                    // Block until interrupted (SIGINT/SIGTERM fires the shutdown hook).
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            return 0;
        } catch (Exception e) {
            log.error("BotRunner launch failed: {}", e.getMessage(), e);
            return 2;
        } finally {
            // fleet.shutdown() is idempotent — safe even if the shutdown hook already fired.
            fleet.shutdown();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignore) {
                // JVM is shutting down — hook already running, cannot remove.
            }
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, BotFleet::new, BotFactory::new));
    }
}
