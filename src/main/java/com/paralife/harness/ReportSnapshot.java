package com.paralife.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of load-harness run state, serialised to JSON / JSONL (D-17).
 *
 * <p><b>snake_case wire format (Round 2 Codex HIGH):</b> Java field names stay camelCase;
 * the ObjectMapper in {@link ReportWriter} is configured with
 * {@code PropertyNamingStrategies.SNAKE_CASE} so all fields serialise to
 * {@code peak_registered}, {@code current_registered}, etc. automatically.
 * There are NO {@code @JsonProperty} annotations on individual fields —
 * the strategy is set once at the mapper level.
 *
 * <p>Two factory paths:
 * <ul>
 *   <li>{@link #header} — static config written once (harness id, URI, target count, JVM version)</li>
 *   <li>{@link #counters} — rolling counters written every report interval</li>
 *   <li>{@link #merge} — overwrite-mode: combines header + counters into one full object</li>
 * </ul>
 *
 * <p>Fields annotated {@code @JsonInclude(NON_NULL)} so the final write's {@code exitReason}
 * only appears when non-null (i.e. on the final report write), and intermediate writes omit it.
 */
@JsonInclude(Include.NON_NULL)
public record ReportSnapshot(
        // Static config (header line in JSONL append mode)
        String harnessId,               // → "harness_id" via SNAKE_CASE strategy
        String serverUri,               // → "server_uri"
        Integer targetCount,            // → "target_count"
        String startWallTime,           // → "start_wall_time"
        String jvmVersion,              // → "jvm_version"
        // Rolling counters
        Integer peakRegistered,         // → "peak_registered"
        Integer currentRegistered,      // → "current_registered"
        Long connectFailuresTotal,      // → "connect_failures_total"
        Long e408ReconnectRequiredTotal, // → "e408_reconnect_required_total"
        Long respawnsTotal,             // → "respawns_total"
        Long actionsSentTotal,          // → "actions_sent_total"
        Long perceptionsReceivedTotal,  // → "perceptions_received_total"
        Long syncsReceivedTotal,        // → "syncs_received_total"
        Long wallTimeSecondsElapsed,    // → "wall_time_seconds_elapsed"
        String exitReason,              // → "exit_reason" (final write only)
        Map<String, Double> serverMetrics // → "server_metrics" (Task 3); empty map, never null
) {
    /**
     * Meter → statistic for the server-side {@code /actuator/metrics} scrape folded into the
     * report (Task 3). Exact meter names — no wildcard {@code /actuator/metrics} endpoint exists,
     * so {@code paralife.backpressure.*} meters are spelled out individually. By-reason breakdown
     * of {@code paralife.admission.rejected} is deferred to BACKLOG.
     *
     * <p>Insertion-ordered (not {@code Map.of}, whose per-JVM salted iteration order shuffled the
     * {@code server_metrics} key order between runs) so the report schema is byte-stable and
     * successive reports diff cleanly.
     */
    public static final Map<String, String> BENCHMARK_METER_NAMES = benchmarkMeterNames();

    private static Map<String, String> benchmarkMeterNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("paralife.tick.work.ms", "MAX");
        m.put("paralife.ws.active.sessions", "VALUE");
        m.put("paralife.backpressure.stalled.sessions", "VALUE");
        m.put("paralife.backpressure.stalled.total", "COUNT");
        m.put("paralife.backpressure.rebound", "COUNT");
        m.put("paralife.backpressure.terminal.dropouts", "COUNT");
        m.put("paralife.admission.rejected", "COUNT");
        return Collections.unmodifiableMap(m);
    }

    /**
     * Build a static-config-only header snapshot. Counter fields are all null so
     * {@code @JsonInclude(NON_NULL)} omits them from the JSON output.
     */
    public static ReportSnapshot header(String harnessId, String serverUri, int targetCount,
                                        String startWallTime, String jvmVersion) {
        return new ReportSnapshot(harnessId, serverUri, targetCount, startWallTime, jvmVersion,
                null, null, null, null, null, null, null, null, null, null, Map.of());
    }

    /**
     * Build a counters-only snapshot. Header fields are null — caller uses
     * {@link #merge(ReportSnapshot, ReportSnapshot)} to combine with header for overwrite mode,
     * or writes directly for JSONL append mode.
     */
    public static ReportSnapshot counters(Integer peakRegistered, Integer currentRegistered,
                                          Long connectFailures, Long e408, Long respawns,
                                          Long actions, Long perceptions, Long syncs,
                                          Long wallSecs, String exitReason) {
        return new ReportSnapshot(null, null, null, null, null,
                peakRegistered, currentRegistered, connectFailures, e408,
                respawns, actions, perceptions, syncs, wallSecs, exitReason, Map.of());
    }

    /**
     * Overwrite-mode merge (OpenCode amendment — header fields must never be lost after the
     * first interval write). Combines static config from {@code header} with rolling counters
     * from {@code counters} into a single snapshot.
     */
    public static ReportSnapshot merge(ReportSnapshot header, ReportSnapshot counters) {
        return new ReportSnapshot(
                header.harnessId, header.serverUri, header.targetCount,
                header.startWallTime, header.jvmVersion,
                counters.peakRegistered, counters.currentRegistered,
                counters.connectFailuresTotal, counters.e408ReconnectRequiredTotal,
                counters.respawnsTotal, counters.actionsSentTotal,
                counters.perceptionsReceivedTotal, counters.syncsReceivedTotal,
                counters.wallTimeSecondsElapsed, counters.exitReason,
                counters.serverMetrics);
    }

    /**
     * Returns a copy of {@code base} whose {@code serverMetrics} is {@code scraped} normalized to
     * the full {@link #BENCHMARK_METER_NAMES} key set — any meter the scraper omitted is null-filled
     * so the category is always visible in the schema, absent only in value.
     */
    public static ReportSnapshot withServerMetrics(ReportSnapshot base, Map<String, Double> scraped) {
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (String meter : BENCHMARK_METER_NAMES.keySet()) {
            normalized.put(meter, scraped.get(meter));
        }
        return new ReportSnapshot(
                base.harnessId, base.serverUri, base.targetCount,
                base.startWallTime, base.jvmVersion,
                base.peakRegistered, base.currentRegistered,
                base.connectFailuresTotal, base.e408ReconnectRequiredTotal,
                base.respawnsTotal, base.actionsSentTotal,
                base.perceptionsReceivedTotal, base.syncsReceivedTotal,
                base.wallTimeSecondsElapsed, base.exitReason,
                normalized);
    }
}
