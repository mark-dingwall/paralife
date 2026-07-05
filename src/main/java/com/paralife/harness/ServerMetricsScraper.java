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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Read-only {@code /actuator/metrics/{name}} scraper for folding server-side meters
 * (tick-drift, rejections, session counts) into the load-harness benchmark report.
 *
 * <p>Fail-soft by design: a missing, erroring, or timed-out meter is omitted from the result,
 * never thrown — a benchmark run never fails on a missing meter. Bounded by an overall ~2s
 * whole-scrape budget: all meters are requested concurrently and harvested within one shared
 * deadline, so a stalled response (even one whose headers arrived) can never stall the caller
 * for {@code meters × timeout}: it runs on the shutdown-hook final-report write path, where a
 * bounded supervisor kill-grace would otherwise lose the most-stressed tier's report.
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
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(2);   // per-request header/connect cap
    private static final Duration TOTAL_BUDGET = Duration.ofSeconds(2);  // whole-scrape harvest cap — bounds the report path

    private final URI actuatorBase;
    private final HttpClient http;
    private final Duration totalBudget;

    public ServerMetricsScraper(URI actuatorBase, HttpClient http) {
        this(actuatorBase, http, TOTAL_BUDGET);
    }

    /** Test seam: inject a short budget so the whole-scrape bound is unit-testable without a 2s wait. */
    ServerMetricsScraper(URI actuatorBase, HttpClient http, Duration totalBudget) {
        this.actuatorBase = actuatorBase;
        this.http = http;
        this.totalBudget = totalBudget;
    }

    /**
     * Derives the actuator base from a {@code --server-uri} WebSocket endpoint:
     * {@code ws://h/ws/world} → {@code http://h/actuator/} ({@code wss}→{@code https}).
     * Root-deployment only (no context-path handling). Pure; unit-tested over test-owned input.
     */
    static URI actuatorBaseFrom(String serverUri) {
        URI u = URI.create(serverUri);
        String scheme = "wss".equalsIgnoreCase(u.getScheme()) ? "https" : "http";
        // getRawAuthority() preserves host:port verbatim, incl. IPv6 brackets ([::1]) that
        // getHost() strips — hand-concatenating a bare IPv6 host yields an unparseable URI.
        return URI.create(scheme + "://" + u.getRawAuthority() + "/actuator/");
    }

    /** name→value for each meter's requested statistic; absent/erroring/timed-out meters omitted. */
    public Map<String, Double> scrape(Map<String, String> meterToStatistic) {
        Map<String, Double> out = new LinkedHashMap<>();
        // Fire every request concurrently, then harvest each within ONE shared deadline. A future's
        // get(timeout) bounds the WHOLE exchange (connect + headers + body); HttpRequest.timeout bounds
        // only header receipt, so a stalled response body could otherwise block the report path past budget.
        Map<String, CompletableFuture<HttpResponse<String>>> inflight = new LinkedHashMap<>();
        for (var e : meterToStatistic.entrySet()) {
            HttpRequest req = HttpRequest.newBuilder(actuatorBase.resolve("metrics/" + e.getKey()))
                    .timeout(REQ_TIMEOUT).GET().build();
            inflight.put(e.getKey(), http.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
        }
        long deadlineNanos = System.nanoTime() + totalBudget.toNanos();
        for (var fe : inflight.entrySet()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            try {
                if (remainingNanos <= 0) {
                    throw new TimeoutException(); // budget spent — omit this and every remaining meter
                }
                HttpResponse<String> res = fe.getValue().get(remainingNanos, TimeUnit.NANOSECONDS);
                if (res.statusCode() == 200) {
                    Double v = parseMetricValue(res.body(), meterToStatistic.get(fe.getKey()));
                    if (v != null) out.put(fe.getKey(), v);
                }
            } catch (InterruptedException ie) {
                // Reporter/shutdown interrupt: restore the flag, cancel in-flight, stop promptly.
                Thread.currentThread().interrupt();
                inflight.values().forEach(f -> f.cancel(true));
                return out;
            } catch (TimeoutException | ExecutionException ex) {
                fe.getValue().cancel(true); // omit; a benchmark never dies (or hangs) on a missing/slow meter
            }
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
