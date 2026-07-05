package com.paralife.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Schema-shape tests for {@link ReportSnapshot#serverMetrics()} / {@link ReportSnapshot#withServerMetrics}.
 *
 * <p>Pure, test-owned values — the SOLE default-suite home for the server_metrics schema check
 * (Task 3). No assertion here (or anywhere in the default suite) depends on a live scrape.
 */
class ReportSnapshotTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private static ReportSnapshot baseSnap() {
        ReportSnapshot header = ReportSnapshot.header("test-harness-01", "ws://localhost:8080/ws/world",
                100, "2026-01-01T00:00:00Z", "21");
        ReportSnapshot counters = ReportSnapshot.counters(
                50, 40, 2L, 1L, 3L, 1000L, 5000L, 200L, 30L, null);
        return ReportSnapshot.merge(header, counters);
    }

    @Test
    void snapshotCarriesServerMetricsBlockSerializedSnakeCase() throws Exception {
        Map<String, Double> server = new LinkedHashMap<>();
        server.put("paralife.tick.work.ms", 11.0);      // test-owned
        server.put("paralife.admission.rejected", 3.0);      // test-owned
        ReportSnapshot snap = ReportSnapshot.withServerMetrics(baseSnap(), server);

        String json = MAPPER.writeValueAsString(snap);        // mirrors ReportWriter's mapper
        assertThat(json).contains("\"server_metrics\"");
        assertThat(snap.serverMetrics()).containsEntry("paralife.tick.work.ms", 11.0);
    }

    @Test
    void bareFactoriesDefaultServerMetricsToEmptyNeverNull() throws Exception {
        // Guards the silent-schema-break vector: header()/counters() are the first-write production
        // path (never wrapped in withServerMetrics). If either passed null instead of Map.of(), the
        // class-level @JsonInclude(NON_NULL) would DROP the server_metrics key entirely.
        ReportSnapshot header = ReportSnapshot.header("h", "ws://localhost:8080/ws/world", 100, "t", "21");
        ReportSnapshot counters = ReportSnapshot.counters(50, 40, 2L, 1L, 3L, 1000L, 5000L, 200L, 30L, null);
        assertThat(header.serverMetrics()).isEqualTo(Map.of());
        assertThat(counters.serverMetrics()).isEqualTo(Map.of());
        // JSON-level positive control: the empty map is present (as {}), not stripped.
        assertThat(MAPPER.writeValueAsString(header)).contains("\"server_metrics\":{}");
    }

    @Test
    void serverMetricsKeyOrderIsDeterministic() {
        // BENCHMARK_METER_NAMES is insertion-ordered (not Map.of), so the normalized key order is a
        // stable schema contract, not a per-JVM-salted shuffle.
        ReportSnapshot snap = ReportSnapshot.withServerMetrics(baseSnap(), Map.of());
        assertThat(snap.serverMetrics().keySet())
                .containsExactly(
                        "paralife.tick.work.ms",
                        "paralife.ws.active.sessions",
                        "paralife.backpressure.stalled.sessions",
                        "paralife.backpressure.stalled.total",
                        "paralife.backpressure.rebound",
                        "paralife.backpressure.terminal.dropouts",
                        "paralife.admission.rejected");
    }

    @Test
    void withServerMetricsPreservesEveryBaseFieldUnchanged() {
        // Guard (not RED-first — no current defect): the 16-arg positional withServerMetrics/merge
        // constructors are transposition-prone. Distinct sentinel per field so any swap of two same-typed
        // components (esp. the adjacent Longs) fails here instead of silently shipping.
        ReportSnapshot base = new ReportSnapshot(
                "hID", "ws://u", 100, "startT", "21",
                50, 40, 2L, 1L, 3L, 1000L, 5000L, 200L, 30L, "duration-reached", Map.of());

        ReportSnapshot out =
                ReportSnapshot.withServerMetrics(base, Map.of("paralife.admission.rejected", 9.0));

        assertThat(out.harnessId()).isEqualTo("hID");
        assertThat(out.serverUri()).isEqualTo("ws://u");
        assertThat(out.targetCount()).isEqualTo(100);
        assertThat(out.startWallTime()).isEqualTo("startT");
        assertThat(out.jvmVersion()).isEqualTo("21");
        assertThat(out.peakRegistered()).isEqualTo(50);
        assertThat(out.currentRegistered()).isEqualTo(40);
        assertThat(out.connectFailuresTotal()).isEqualTo(2L);
        assertThat(out.e408ReconnectRequiredTotal()).isEqualTo(1L);
        assertThat(out.respawnsTotal()).isEqualTo(3L);
        assertThat(out.actionsSentTotal()).isEqualTo(1000L);
        assertThat(out.perceptionsReceivedTotal()).isEqualTo(5000L);
        assertThat(out.syncsReceivedTotal()).isEqualTo(200L);
        assertThat(out.wallTimeSecondsElapsed()).isEqualTo(30L);
        assertThat(out.exitReason()).isEqualTo("duration-reached");
        assertThat(out.serverMetrics()).containsEntry("paralife.admission.rejected", 9.0);
    }

    @Test
    void absentMeterNormalizesToNullValuedCategoryKey() throws Exception {
        // scraper omitted every meter -> withServerMetrics normalizes to the full BENCHMARK_METER_NAMES key
        // set, value null. Completeness is thus enforceable: every category key is present even with zero
        // live data.
        ReportSnapshot snap = ReportSnapshot.withServerMetrics(baseSnap(), Map.of());
        assertThat(snap.serverMetrics().keySet())
                .containsExactlyInAnyOrderElementsOf(ReportSnapshot.BENCHMARK_METER_NAMES.keySet());
        assertThat(snap.serverMetrics().get("paralife.tick.work.ms")).isNull(); // absent -> null, not missing
        // JSON-level: the null-valued key is VISIBLE (not stripped) -- pins Jackson content-inclusion behaviour
        assertThat(MAPPER.writeValueAsString(snap)).contains("\"paralife.tick.work.ms\":null");
    }
}
