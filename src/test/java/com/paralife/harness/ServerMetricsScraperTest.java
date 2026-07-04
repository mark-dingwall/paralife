package com.paralife.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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
            {"name":"paralife.tick.drift.millis",
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
}
