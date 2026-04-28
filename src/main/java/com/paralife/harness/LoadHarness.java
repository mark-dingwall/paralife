package com.paralife.harness;

import com.paralife.bot.BotClient;
import com.paralife.bot.BotFactory;
import com.paralife.bot.BotFleet;
import com.paralife.bot.BotIdentity;
import com.paralife.bot.RampUpSpec;
import com.paralife.bot.SpeciesMix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone Paralife load harness (Phase 18 D-15).
 *
 * <p>Zero Spring annotations — pure process entry point.
 * No SpringApplication is used. Picocli handles argument parsing;
 * {@link BotFleet} manages the VT-per-bot lifecycle.
 *
 * <p><b>Round 2 Codex HIGH — Callable&lt;Integer&gt;:</b>
 * Implements {@link Callable}{@code <Integer>}; {@link #call()} returns the exit code.
 * Picocli's {@code CommandLine.execute(args)} routes the return value to the process
 * exit code. The process exit call appears ONLY in {@link #main(String[])}.
 *
 * <p><b>Round 2 Codex HIGH — {@code ${env:VAR}} syntax:</b>
 * All env-var {@code defaultValue} strings use {@code ${env:PARALIFE_HARNESS_*}} syntax.
 * Bare {@code ${VAR}} resolves from system properties, NOT environment variables.
 *
 * <p><b>Round 2 Codex HIGH — single "signal" exit reason:</b>
 * SIGINT and SIGTERM cannot be reliably distinguished by JVM shutdown hooks.
 * A single hook with {@code exitReason = "signal"} is used for both.
 *
 * <p><b>Round 2 Claude+OpenCode MEDIUM — shutdown hook cleanup:</b>
 * The hook {@code Thread} reference is captured and removed in a {@code finally} block
 * to prevent hook accumulation across test JVM runs.
 */
@Command(name = "load-harness", mixinStandardHelpOptions = true,
        description = "Paralife external load harness (Phase 18 D-15).")
public final class LoadHarness implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(LoadHarness.class);

    // Round 2 Codex HIGH: env-var defaults use ${env:VAR} syntax.
    // Bare ${VAR} would resolve from Java system properties, NOT environment variables.

    @Option(names = "--server-uri", required = true,
            defaultValue = "${env:PARALIFE_HARNESS_SERVER_URI}",
            description = "WebSocket server URI (e.g. ws://localhost:8080/ws/world).")
    String serverUri;

    @Option(names = "--count", required = true,
            defaultValue = "${env:PARALIFE_HARNESS_COUNT}",
            description = "Number of bots to launch.")
    int count;

    @Option(names = "--harness-id",
            defaultValue = "${env:PARALIFE_HARNESS_HARNESS_ID}",
            description = "Harness identity (auto-generated if absent).")
    String harnessId;

    @Option(names = "--ramp-up",
            defaultValue = "${env:PARALIFE_HARNESS_RAMP_UP:-rate:50}",
            converter = RampUpConverter.class,
            description = "instant | rate:<n> | wave:<count>:<sleepMs>. Default rate:50.")
    RampUpSpec rampUp;

    @Option(names = "--species-mix",
            defaultValue = "${env:PARALIFE_HARNESS_SPECIES_MIX:-balanced}",
            converter = SpeciesMixConverter.class,
            description = "balanced | <C>:<M>:<S>. Default balanced.")
    SpeciesMix speciesMix;

    @Option(names = "--duration",
            defaultValue = "${env:PARALIFE_HARNESS_DURATION:-0}",
            description = "Run duration in seconds (0 = indefinite). Default 0.")
    int durationSeconds;

    @Option(names = "--report-out",
            defaultValue = "${env:PARALIFE_HARNESS_REPORT_OUT}",
            description = "Report output path. Default ./harness-<id>-report.json.")
    Path reportOut;

    @Option(names = "--report-mode",
            defaultValue = "${env:PARALIFE_HARNESS_REPORT_MODE:-overwrite}",
            description = "overwrite | append. Default overwrite.")
    String reportMode;

    @Option(names = "--report-interval",
            defaultValue = "${env:PARALIFE_HARNESS_REPORT_INTERVAL:-30}",
            description = "Report write interval in seconds. Range 10..300. Default 30.")
    int reportIntervalSeconds;

    // Retained for overwrite-mode header-merge across interval writes.
    private ReportSnapshot initialHeader;

    /**
     * Process entry point. Picocli executes {@link #call()} and routes the returned Integer
     * exit code to the process via a single exit call at the end of this method.
     * This is the ONLY location where process exit is initiated; call() and runInternal()
     * never exit the process directly — that preserves testability and composition.
     */
    public static void main(String[] args) {
        // Round 2 Codex HIGH: Callable<Integer> + execute(args). Keep the exit call here only.
        int rc = new CommandLine(new LoadHarness()).execute(args);
        System.exit(rc);
    }

    @Override
    public Integer call() {
        validateAndDefault();
        return runInternal();
    }

    /**
     * Validates option ranges and auto-generates defaults for omitted optional flags.
     * Called from {@link #call()} before {@link #runInternal()}.
     */
    void validateAndDefault() {
        if (count < 1) {
            throw new IllegalArgumentException("--count must be >= 1 (got " + count + ")");
        }
        if (reportIntervalSeconds < 10 || reportIntervalSeconds > 300) {
            throw new IllegalArgumentException(
                    "--report-interval must be in range 10..300 (got " + reportIntervalSeconds + ")");
        }
        if (!"overwrite".equals(reportMode) && !"append".equals(reportMode)) {
            throw new IllegalArgumentException(
                    "--report-mode must be overwrite|append (got " + reportMode + ")");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException(
                    "--duration must be >= 0 (got " + durationSeconds + ")");
        }
        if (harnessId == null || harnessId.isBlank()) {
            harnessId = generateHarnessId();
        }
        if (reportOut == null) {
            reportOut = Path.of("./harness-" + harnessId + "-report.json");
        }
    }

    /**
     * Generates an auto harness id: {@code harness-<hex>}, truncated to 32 chars.
     * Format: alphanumeric + dash, less than 32 chars.
     */
    private static String generateHarnessId() {
        String hex = Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
        String full = "harness-" + hex;
        return full.substring(0, Math.min(32, full.length()));
    }

    /**
     * Main run body. Returns the exit code (0 = success, 2 = error).
     * Lifecycle:
     * <ol>
     *   <li>Write initial report (header or merged).</li>
     *   <li>Register single shutdown hook (Round 2 Codex HIGH: one hook, exitReason="signal").</li>
     *   <li>Launch fleet via {@link BotFleet}.</li>
     *   <li>Wait for duration or signal.</li>
     *   <li>Write final report with {@code exitReason}.</li>
     *   <li>Shutdown fleet (idempotent — Plan 04 ships idempotency in BotFleet).</li>
     *   <li>Remove shutdown hook in finally (Round 2 Claude+OpenCode MEDIUM).</li>
     * </ol>
     */
    int runInternal() {
        log.info("LoadHarness starting — harness-id={} server={} count={} duration={}s ramp-up={}",
                harnessId, serverUri, count,
                durationSeconds == 0 ? "indefinite" : Integer.toString(durationSeconds),
                rampUp);

        BotFleet fleet = new BotFleet();
        BotFactory factory = new BotFactory(serverUri);
        BotIdentity identity = BotIdentity.harness(harnessId);
        ReportWriter writer = new ReportWriter();
        Instant startedAt = Instant.now();
        AtomicReference<String> exitReason = new AtomicReference<>(null);
        CountDownLatch exitLatch = new CountDownLatch(1);

        // Build initial header snapshot (retained across all interval writes for overwrite-mode merge).
        initialHeader = ReportSnapshot.header(harnessId, serverUri, count,
                startedAt.toString(), System.getProperty("java.version"));

        // Write initial report.
        try {
            if ("append".equals(reportMode)) {
                writer.writeJsonlHeader(reportOut, initialHeader);
            } else {
                writer.writeOverwrite(reportOut, initialHeader);
            }
        } catch (Exception e) {
            log.error("Initial report write failed: {}", e.getMessage());
            return 2;
        }

        // Periodic counter-write virtual thread.
        Thread reporterVT = Thread.ofVirtual().start(() -> {
            while (exitReason.get() == null) {
                try {
                    Thread.sleep(reportIntervalSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (exitReason.get() != null) return;
                try {
                    writeCounters(writer, fleet, startedAt, null);
                } catch (Exception e) {
                    log.warn("Periodic report write failed: {}", e.getMessage());
                }
            }
        });

        // Round 2 Codex HIGH: single shutdown hook, single exitReason = "signal".
        // SIGINT and SIGTERM cannot be reliably distinguished by JVM shutdown hooks.
        // Round 2 Claude+OpenCode MEDIUM: capture the Thread reference for removal in finally.
        Thread shutdownHook = new Thread(() -> {
            exitReason.compareAndSet(null, "signal");
            fleet.shutdown();
            exitLatch.countDown();
        }, "load-harness-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            // Launch the fleet.
            try {
                fleet.launch(serverUri, count, identity, rampUp, speciesMix, factory);
            } catch (Exception e) {
                log.error("Fleet launch failed: {}", e.getMessage(), e);
                exitReason.set("fatal-error");
                writeFinalReport(writer, fleet, startedAt, exitReason.get());
                return 2;
            }

            // Wait for duration or signal.
            if (durationSeconds > 0) {
                try {
                    if (exitLatch.await(durationSeconds, TimeUnit.SECONDS)) {
                        // Signalled before duration expired — hook already set exitReason.
                    } else {
                        exitReason.compareAndSet(null, "duration-reached");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exitReason.compareAndSet(null, "signal");
                }
            } else {
                // Run indefinitely until SIGINT/SIGTERM.
                try {
                    exitLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exitReason.compareAndSet(null, "signal");
                }
            }

            String reason = exitReason.get() != null ? exitReason.get() : "duration-reached";
            writeFinalReport(writer, fleet, startedAt, reason);
            fleet.shutdown(); // idempotent — BotFleet.shutdownDone CAS guards double-call (Plan 04).
            return 0;

        } finally {
            // Round 2 Claude+OpenCode MEDIUM: remove the shutdown hook so test JVMs don't
            // accumulate hooks across test runs. IllegalStateException means JVM is already
            // shutting down and the hook is executing — nothing to remove.
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down — hook is being executed; removal not possible.
            }
            reporterVT.interrupt();
        }
    }

    private void writeCounters(ReportWriter w, BotFleet fleet,
                               Instant startedAt, String exitReason) throws java.io.IOException {
        ReportSnapshot counters = computeCountersSnapshot(fleet, startedAt, exitReason);
        if ("append".equals(reportMode)) {
            w.appendJsonlCounter(reportOut, counters);
        } else {
            // Overwrite-mode: always merge with initialHeader so header fields are never lost.
            w.writeOverwrite(reportOut, ReportSnapshot.merge(initialHeader, counters));
        }
    }

    private void writeFinalReport(ReportWriter w, BotFleet fleet,
                                  Instant startedAt, String exitReason) {
        try {
            writeCounters(w, fleet, startedAt, exitReason);
        } catch (Exception e) {
            log.warn("Final report write failed: {}", e.getMessage());
        }
    }

    /**
     * Build a counters snapshot from MONOTONIC counters in BotFleet / BotClient.
     * (Round 2 Codex MEDIUM: counters must be monotonic, not computed from current state.)
     *
     * <ul>
     *   <li>{@code connect_failures_total} — {@link BotFleet#connectFailuresTotal()} (new monotonic counter)</li>
     *   <li>{@code syncs_received_total} — sum of {@link BotClient#getSyncsReceivedCount()} (new counter)</li>
     *   <li>actions / perceptions / respawns / e408 — existing monotonic counters per BotClient</li>
     * </ul>
     */
    private ReportSnapshot computeCountersSnapshot(BotFleet fleet,
                                                   Instant startedAt, String exitReason) {
        long actions = 0, perceptions = 0, syncs = 0, respawns = 0, e408 = 0;
        for (BotClient b : fleet.getBots()) {
            actions     += b.getActionCount();
            perceptions += b.getPerceptionCount();
            respawns    += b.getRespawnCount();
            e408        += b.getE408ReconnectRequiredCount();  // added in Task 2
            syncs       += b.getSyncsReceivedCount();          // added in Task 2
        }
        long failures = fleet.connectFailuresTotal();           // added in Task 2
        long elapsedSec = Duration.between(startedAt, Instant.now()).toSeconds();
        return ReportSnapshot.counters(
                fleet.peakRegistered(), fleet.currentRegistered(),
                failures, e408, respawns,
                actions, perceptions, syncs,
                elapsedSec, exitReason);
    }
}
