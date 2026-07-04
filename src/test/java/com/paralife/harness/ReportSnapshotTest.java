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
        server.put("paralife.tick.drift.millis", 11.0);      // test-owned
        server.put("paralife.admission.rejected", 3.0);      // test-owned
        ReportSnapshot snap = ReportSnapshot.withServerMetrics(baseSnap(), server);

        String json = MAPPER.writeValueAsString(snap);        // mirrors ReportWriter's mapper
        assertThat(json).contains("\"server_metrics\"");
        assertThat(snap.serverMetrics()).containsEntry("paralife.tick.drift.millis", 11.0);
    }

    @Test
    void absentMeterNormalizesToNullValuedCategoryKey() throws Exception {
        // scraper omitted every meter -> withServerMetrics normalizes to the full BENCHMARK_METER_NAMES key
        // set, value null. Completeness is thus enforceable: every category key is present even with zero
        // live data.
        ReportSnapshot snap = ReportSnapshot.withServerMetrics(baseSnap(), Map.of());
        assertThat(snap.serverMetrics().keySet())
                .containsExactlyInAnyOrderElementsOf(ReportSnapshot.BENCHMARK_METER_NAMES.keySet());
        assertThat(snap.serverMetrics().get("paralife.tick.drift.millis")).isNull(); // absent -> null, not missing
        // JSON-level: the null-valued key is VISIBLE (not stripped) -- pins Jackson content-inclusion behaviour
        assertThat(MAPPER.writeValueAsString(snap)).contains("\"paralife.tick.drift.millis\":null");
    }
}
