package com.paralife.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ServerMetricsScraper}'s pure parsing/derivation statics.
 *
 * <p>All inputs here are test-owned canned JSON / URI strings — asserting extracted values is
 * parser-mechanism testing, not a live-aggregate assert (see CLAUDE.md firewall corollary).
 * {@link #scrape} itself (live HTTP GET) is exercised by later tasks, not here.
 */
class ServerMetricsScraperTest {

    @Test
    void parsesRequestedStatisticFromCannedActuatorJson() {
        String json = """
            {"name":"paralife.tick.work.ms",
             "measurements":[{"statistic":"COUNT","value":50.0},
                             {"statistic":"MAX","value":11.0}]}""";
        // test OWNS this input → asserting extracted values is parser-mechanism, not a live-aggregate assert
        assertThat(ServerMetricsScraper.parseMetricValue(json, "MAX")).isEqualTo(11.0);
        assertThat(ServerMetricsScraper.parseMetricValue(json, "COUNT")).isEqualTo(50.0);
    }

    @Test
    void returnsNullForAbsentStatisticOrMalformedJson() {   // positive/negative pair
        String json = """
            {"name":"x","measurements":[{"statistic":"COUNT","value":1.0}]}""";
        assertThat(ServerMetricsScraper.parseMetricValue(json, "MAX")).isNull();      // absent stat
        assertThat(ServerMetricsScraper.parseMetricValue("not json", "COUNT")).isNull(); // malformed
        // present statistic but missing/non-numeric value → null, never a false 0.0
        assertThat(ServerMetricsScraper.parseMetricValue(
            "{\"measurements\":[{\"statistic\":\"MAX\"}]}", "MAX")).isNull();          // no value node
        assertThat(ServerMetricsScraper.parseMetricValue(
            "{\"measurements\":[{\"statistic\":\"MAX\",\"value\":\"NaN\"}]}", "MAX")).isNull(); // non-numeric
    }

    @Test
    void derivesActuatorBaseFromWsServerUri() {   // pure URI mechanism — test owns the input
        // ws→http, /ws/world→/actuator/. This is the ONE positive control that the retrieval
        // path targets the right endpoint; the fail-soft scrape() otherwise hides a wrong base.
        assertThat(ServerMetricsScraper.actuatorBaseFrom("ws://h:8080/ws/world"))
                .isEqualTo(URI.create("http://h:8080/actuator/"));
        assertThat(ServerMetricsScraper.actuatorBaseFrom("wss://h:8443/ws/world"))
                .isEqualTo(URI.create("https://h:8443/actuator/"));
    }

    @Test
    void derivesHttpsForWssRegardlessOfCase() {   // LOW-7: schemes are case-insensitive
        assertThat(ServerMetricsScraper.actuatorBaseFrom("wss://h/ws/world").getScheme()).isEqualTo("https");
        assertThat(ServerMetricsScraper.actuatorBaseFrom("WSS://h/ws/world").getScheme()).isEqualTo("https");
        assertThat(ServerMetricsScraper.actuatorBaseFrom("ws://h/ws/world").getScheme()).isEqualTo("http");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scrapeOmitsStalledMeterAndStaysWithinBudget() {
        // Mechanism, test-owned: one meter's response completes instantly; the other NEVER completes
        // (simulates a stalled body — the exact case HttpRequest.timeout does NOT bound). scrape() must
        // harvest the fast meter and OMIT the stalled one, returning within the injected budget.
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"measurements\":[{\"statistic\":\"MAX\",\"value\":7.0}]}");

        CompletableFuture<HttpResponse<String>> fast = CompletableFuture.completedFuture(ok);
        CompletableFuture<HttpResponse<String>> stalled = new CompletableFuture<>(); // never completes
        when(http.sendAsync(any(HttpRequest.class), any())).thenAnswer(inv -> {
            HttpRequest req = inv.getArgument(0);
            return req.uri().getPath().endsWith("/fast") ? fast : stalled;
        });

        var scraper = new ServerMetricsScraper(
                URI.create("http://h/actuator/"), http, Duration.ofMillis(150));
        Map<String, String> meters = new LinkedHashMap<>();
        meters.put("fast", "MAX");
        meters.put("slow", "MAX");

        long t0 = System.nanoTime();
        Map<String, Double> out = scraper.scrape(meters);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(out).containsExactly(entry("fast", 7.0)); // fast harvested; stalled omitted (positive+negative pair)
        assertThat(elapsedMs).isLessThan(1000);              // positive control: bounded by the 150ms budget, not the 2s per-req timeout
    }
}
