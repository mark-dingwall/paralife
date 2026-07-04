package com.paralife.harness;

import com.paralife.bot.BotClient;
import com.paralife.bot.BotFactory;
import com.paralife.bot.BotFleet;
import com.paralife.bot.BotIdentity;
import com.paralife.bot.RampUpSpec;
import com.paralife.bot.SpeciesMix;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
            defaultValue = "${env:PARALIFE_HARNESS_ID}",
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

    // Server /actuator scraper (Task 3), built once per run from --server-uri. The scrape is bounded
    // by an overall ~2s budget (see ServerMetricsScraper) so an overloaded server can't stall the
    // shutdown-hook final-report write. volatile: read on the shutdown-hook thread (scrape), written
    // on the run thread — same cross-thread visibility contract as the active* live-state fields.
    private volatile ServerMetricsScraper metricsScraper;
    // Owned scraper HTTP client, closed in runInternal()'s finally (Java 21 HttpClient is
    // AutoCloseable) so a reused instance / leak-sensitive test JVM doesn't accrete client threads.
    private volatile HttpClient metricsHttp;

    // H-02/H-03 + M-01 (Round B): live state retained as instance fields so the
    // shutdown hook body and tests can drive the same final-report code path.
    // CAS-guarded single-write of the final report.
    private final AtomicBoolean finalReportWritten = new AtomicBoolean(false);
    // Live state captured at the start of runInternal(); cleared/replaced on each call.
    private volatile BotFleet activeFleet;
    private volatile ReportWriter activeWriter;
    private volatile Instant activeStartedAt;
    private volatile Thread activeReporterVT;
    private volatile AtomicReference<String> activeExitReason;
    private volatile CountDownLatch activeExitLatch;

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
        // M-02 (Round B): WARN above the D-02 5000-VT design ceiling per JVM. Not an error
        // because the ceiling is design guidance, not a hard limit, but operators should split
        // the load across multiple harness JVMs rather than push past it.
        if (count > 5000) {
            log.warn("--count={} exceeds D-02 5000-VT design ceiling per JVM. " +
                    "Recommend splitting across multiple harness JVMs.", count);
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

        // Reset CAS so a single LoadHarness instance can be reused across calls (tests).
        finalReportWritten.set(false);

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

        // Build the scraper AFTER the initial write (which doesn't need it) so an initial-write
        // early-return can't bypass the finally that closes the owned client and leak it.
        metricsHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        metricsScraper = new ServerMetricsScraper(ServerMetricsScraper.actuatorBaseFrom(serverUri), metricsHttp);

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

        // Publish live state so the shutdown hook body (and the test seam below) can call
        // writeFinalReportOnce() / fleet.shutdown() / exitLatch.countDown() against the
        // same instances the duration path uses.
        activeFleet = fleet;
        activeWriter = writer;
        activeStartedAt = startedAt;
        activeReporterVT = reporterVT;
        activeExitReason = exitReason;
        activeExitLatch = exitLatch;

        // H-02/H-03 (Round B): single shutdown hook performs the FINAL report write itself
        // before fleet.shutdown(), so the snapshot is taken while the bot list still has
        // accumulated counters. The hook completes the write before returning so JVM halt
        // cannot truncate it. SIGINT and SIGTERM cannot be reliably distinguished by JVM
        // shutdown hooks; both produce exitReason = "signal".
        // Round 2 Claude+OpenCode MEDIUM: capture the Thread reference for removal in finally.
        Thread shutdownHook = new Thread(this::shutdownHookBody, "load-harness-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            // Launch the fleet.
            try {
                fleet.launch(serverUri, count, identity, rampUp, speciesMix, factory);
            } catch (Exception e) {
                log.error("Fleet launch failed: {}", e.getMessage(), e);
                exitReason.set("fatal-error");
                writeFinalReportOnce();
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

            // Duration-reached path: hook may already have run the final write (signal-then-duration
            // race). writeFinalReportOnce CAS-guards that case to a no-op.
            exitReason.compareAndSet(null, "duration-reached");
            writeFinalReportOnce();
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
            // Close the owned scraper client — the reporter VT is already drained (writeFinalReportOnce
            // interrupt+join above), so no scrape is in flight and close() returns promptly. Guarded so a
            // close failure (UncheckedIOException) can't shadow the return or skip the active* cleanup below.
            if (metricsHttp != null) {
                try {
                    metricsHttp.close();
                } catch (RuntimeException ignored) {
                    // best-effort; a reused instance rebuilds the client on its next run
                }
                metricsHttp = null;
            }
            // Release strong refs so a reused harness instance doesn't pin prior state.
            activeFleet = null;
            activeWriter = null;
            activeStartedAt = null;
            activeReporterVT = null;
            activeExitReason = null;
            activeExitLatch = null;
        }
    }

    /**
     * Body of the JVM shutdown hook. Package-private so {@code LoadHarnessIntegrationTest}
     * can drive the signal path deterministically without spawning a child JVM.
     *
     * <p>Order: set exit_reason -> writeFinalReportOnce -> fleet.shutdown -> exitLatch.countDown.
     * The final-report write happens BEFORE fleet.shutdown() so the bot list is still populated
     * when the snapshot is taken (H-03). CAS-guarded single write means the duration path's
     * subsequent writeFinalReportOnce() is a no-op (H-02).
     */
    void shutdownHookBody() {
        AtomicReference<String> reasonRef = activeExitReason;
        if (reasonRef != null) {
            reasonRef.compareAndSet(null, "signal");
        }
        writeFinalReportOnce();
        BotFleet f = activeFleet;
        if (f != null) {
            f.shutdown();
        }
        CountDownLatch latch = activeExitLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * CAS-guarded single execution of the final-report write. Drains the periodic reporter VT
     * first (M-01) so it cannot race the final write on the temp file or against ReportWriter
     * state. Subsequent invocations are no-ops, regardless of which path (signal hook or
     * duration-reached main thread) wins the CAS.
     */
    private void writeFinalReportOnce() {
        if (!finalReportWritten.compareAndSet(false, true)) {
            return;
        }
        Thread vt = activeReporterVT;
        if (vt != null) {
            vt.interrupt();
            try {
                vt.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AtomicReference<String> reasonRef = activeExitReason;
        String reason = (reasonRef != null && reasonRef.get() != null)
                ? reasonRef.get()
                : "duration-reached";
        ReportWriter w = activeWriter;
        BotFleet f = activeFleet;
        Instant t0 = activeStartedAt;
        if (w != null && f != null && t0 != null) {
            writeFinalReport(w, f, t0, reason);
        }
    }

    /** Test seam: live fleet during runInternal(). null when not running. */
    BotFleet activeFleetForTest() {
        return activeFleet;
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
        ReportSnapshot counters = ReportSnapshot.counters(
                fleet.peakRegistered(), fleet.currentRegistered(),
                failures, e408, respawns,
                actions, perceptions, syncs,
                elapsedSec, exitReason);
        return ReportSnapshot.withServerMetrics(counters,
                metricsScraper.scrape(ReportSnapshot.BENCHMARK_METER_NAMES));
    }
}
