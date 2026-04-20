package com.paralife.websocket;

import com.paralife.metrics.WebSocketMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 15-10 Task 3: actuator reachability check for the two live Micrometer
 * meters. A sibling {@code WebSocketMetricsWiringTest} proves end-to-end wiring
 * (register → gauge / onTick → summary) without bean priming.
 *
 * <p>/actuator/metrics/&lt;name&gt; can return 404 for a DistributionSummary
 * that has recorded zero samples. We prime {@code tick.frame.bytes} in
 * {@link #primeMeters()} to make the reachability assertion deterministic.
 * The Gauge always has a current value (AtomicInteger(0)) and needs no priming.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16",
        "management.endpoints.web.exposure.include=health,info,metrics"
})
class MetricsEndpointIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired WebSocketMetrics metrics;

    @BeforeEach
    void primeMeters() {
        // Prime DistributionSummary so /actuator/metrics/<name> returns a
        // non-empty `measurements` array. Without priming, an unsampled meter
        // can return 404 on Spring Boot Actuator depending on registry config.
        metrics.recordFrameSize(100);
        // Gauge wraps AtomicInteger(0) — always reachable, no priming needed.
    }

    @Test
    void activeSessionsMetricReachable() {
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + port + "/actuator/metrics/paralife.ws.active.sessions",
                String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("measurements"),
                "Expected Micrometer measurements payload: " + resp.getBody());
    }

    @Test
    void tickFrameBytesMetricReachable() {
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + port + "/actuator/metrics/paralife.ws.tick.frame.bytes",
                String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("measurements"));
    }

    @Test
    void bytesSavedMetricIsAbsent() {
        // The third meter is deferred this phase (SCHEMA §13).
        // Actuator should 404 — no such meter is registered.
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + port + "/actuator/metrics/paralife.ws.bytes.saved",
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "bytes.saved must NOT be exposed this phase (deferred per SCHEMA §13)");
    }
}
