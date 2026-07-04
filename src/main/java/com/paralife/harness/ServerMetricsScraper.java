package com.paralife.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only {@code /actuator/metrics/{name}} scraper for folding server-side meters
 * (tick-drift, rejections, session counts) into the load-harness benchmark report.
 *
 * <p>Fail-soft by design: a missing, erroring, or timed-out meter is omitted from the result,
 * never thrown — a benchmark run never fails on a missing meter. Bounded 2s request timeout so
 * the scrape can never hang the caller (it runs on a shutdown-hook write path in later tasks).
 *
 * <p>The meter set is heterogeneous — Counter→{@code COUNT}, Gauge→{@code VALUE},
 * DistributionSummary→{@code MAX} — so the statistic to read is per meter, not one for the
 * whole list.
 *
 * <p>Aggregate-only in P21: two-tag counters (e.g. {@code paralife.admission.rejected}
 * tagged by {@code reason}+{@code source}) and multi-bucket gauges (e.g.
 * {@code paralife.ws.active.sessions}) are read via the base endpoint, which returns the sum
 * across all tags/buckets — a whole-server figure. Per-tag breakdown is deferred to
 * {@code BACKLOG.md} §Phase-21 follow-ups.
 */
public final class ServerMetricsScraper {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(2); // bounded — never hang the report path

    private final URI actuatorBase;
    private final HttpClient http;

    public ServerMetricsScraper(URI actuatorBase, HttpClient http) {
        this.actuatorBase = actuatorBase;
        this.http = http;
    }

    /**
     * Derives the actuator base from a {@code --server-uri} WebSocket endpoint:
     * {@code ws://h/ws/world} → {@code http://h/actuator/} ({@code wss}→{@code https}).
     * Root-deployment only (no context-path handling). Pure; unit-tested over test-owned input.
     */
    static URI actuatorBaseFrom(String serverUri) {
        URI u = URI.create(serverUri);
        String scheme = "wss".equals(u.getScheme()) ? "https" : "http";
        int port = u.getPort();
        return URI.create(scheme + "://" + u.getHost() + (port < 0 ? "" : ":" + port) + "/actuator/");
    }

    /** name→value for each meter's requested statistic; absent/erroring/timed-out meters omitted. */
    public Map<String, Double> scrape(Map<String, String> meterToStatistic) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : meterToStatistic.entrySet()) {
            try {
                HttpRequest req = HttpRequest.newBuilder(actuatorBase.resolve("metrics/" + e.getKey()))
                        .timeout(REQ_TIMEOUT).GET().build();  // bounded: an overloaded server omits, never stalls
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    Double v = parseMetricValue(res.body(), e.getValue());
                    if (v != null) out.put(e.getKey(), v);
                }
            } catch (InterruptedException ie) {
                // Reporter/shutdown interrupt: restore the flag and stop promptly rather than
                // blocking up to REQ_TIMEOUT on each remaining meter.
                Thread.currentThread().interrupt();
                return out;
            } catch (Exception ignored) { /* omit; a benchmark never dies (or hangs) on a missing meter */ }
        }
        return out;
    }

    static Double parseMetricValue(String json, String statistic) {
        try {
            JsonNode ms = M.readTree(json).path("measurements");
            for (JsonNode m : ms)
                if (statistic.equals(m.path("statistic").asText())) {
                    JsonNode v = m.path("value");
                    return v.isNumber() ? v.asDouble() : null;  // missing/non-numeric → null, NOT a false 0.0
                }
            return null;
        } catch (Exception e) { return null; }
    }
}
