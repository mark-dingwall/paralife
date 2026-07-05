package com.paralife.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Task 4 — live end-to-end retrieval path for {@link ServerMetricsScraper}: URI derivation
 * ({@link ServerMetricsScraper#actuatorBaseFrom}) + an actual HTTP scrape against a running
 * Spring Boot actuator, self-booted on a random port (no external server, no bot fleet).
 *
 * <p>{@code paralife.ws.active.sessions} is an eagerly-registered Gauge (backed by an
 * {@code AtomicInteger(0)}) — it reads {@code 0.0} even with zero connected bots, so this is a
 * one-meter liveness probe of the scrape path, not a scenario test.
 *
 * <p>{@code @Tag("slow")}: excluded from the default {@code ./gradlew test} gate (firewall —
 * live-server presence assertions are legal only outside the default suite); runs under
 * {@code ./gradlew test -PincludeLong=true}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("slow")
class ScrapeLiveIntegrationTest {

    @LocalServerPort int port;

    @Test
    void scrapePopulatesServerMetricsAgainstLiveServer() {
        try (HttpClient http = HttpClient.newHttpClient()) {
            var scraper = new ServerMetricsScraper(
                    ServerMetricsScraper.actuatorBaseFrom("ws://localhost:" + port + "/ws/world"),
                    http);

            Map<String, Double> result = scraper.scrape(Map.of("paralife.ws.active.sessions", "VALUE"));

            assertThat(result.get("paralife.ws.active.sessions")).isNotNull();
        }
    }
}
