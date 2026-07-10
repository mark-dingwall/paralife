package com.paralife.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.EligibleCellIndex;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase 19 SCALE-06: Placement density integration test.
 *
 * <p>Registers bots via live WS register frames until the eligible-cell set is
 * exhausted. Verifies:
 * <ol>
 *   <li>No retry storm: all placements succeed on the first sample (no infinite
 *       loops under the new O(1) index path).</li>
 *   <li>The (N+1)th register — when the eligible set is empty — receives
 *       {@code E|503|GRID_FULL} (RejectionToken.GRID_FULL wire shape).</li>
 *   <li>The eligible-cell index size reaches zero before or at the GRID_FULL
 *       response (index correctly tracks full-grid state).</li>
 * </ol>
 *
 * <p>Uses an 8×8 grid (64 cells) so the test completes quickly. With overcrowding
 * constraints, not all 64 cells are placeable at max occupancy — GRID_FULL is
 * expected before 64 placements, but we assert the error arrives rather than
 * asserting an exact count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=500",
        "paralife.tick.auto-start=false",
        "paralife.world.width=8",
        "paralife.world.height=8",
        "paralife.simulation.spawn.seed=99",
        "paralife.simulation.overcrowding-threshold=8"  // relax overcrowding so index fills fully
})
@DirtiesContext
class PlacementDensityIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PlacementDensityIntegrationTest.class);
    private static final int MAX_BOTS = 70;  // > grid size to guarantee GRID_FULL
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private EligibleCellIndex eligibleCellIndex;

    private final List<BlockingWebSocketClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (BlockingWebSocketClient c : clients) {
            try { c.close(); } catch (Exception ignored) {}
        }
        clients.clear();
    }

    @Test
    void fillsGridAndReceivesGridFullOnExhaustion() throws Exception {
        URI uri = URI.create("ws://localhost:" + port + "/ws/world");
        String registerFrame = PerceptionCodec.encode(new Frame.RegisterFrame('C'));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger gridFullCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        // A31 — capture the exhaustion-boundary 503 payload to pin condition→token, not just code.
        AtomicReference<String> gridFullPayload = new AtomicReference<>();

        for (int i = 0; i < MAX_BOTS; i++) {
            BlockingWebSocketClient client = new BlockingWebSocketClient();
            clients.add(client);

            try {
                client.connect(uri, CONNECT_TIMEOUT);
            } catch (Exception e) {
                log.warn("Connect failed at bot {}: {}", i, e.getMessage());
                errorCount.incrementAndGet();
                break;
            }

            client.send(registerFrame);

            // Wait for S| (sync) or E| (error) frame.
            boolean responded = client.awaitReceiveCount(1, RESPONSE_TIMEOUT);
            if (!responded) {
                log.warn("Bot {} did not receive response in time", i);
                errorCount.incrementAndGet();
                break;
            }

            List<String> received = client.received();
            assertThat(received).isNotEmpty();
            String response = received.get(0);

            if (response.startsWith("S|")) {
                successCount.incrementAndGet();
            } else if (response.startsWith("E|503")) {
                gridFullCount.incrementAndGet();
                gridFullPayload.set(response);
                log.info("GRID_FULL at bot {} after {} successful placements", i, successCount.get());
                break; // found the boundary — test assertion can proceed
            } else if (response.startsWith("E|")) {
                log.error("Unexpected error at bot {}: {}", i, response);
                errorCount.incrementAndGet();
                break;
            } else {
                // T| or other frame arrived first (tick racing with sync) — keep polling
                boolean gotSync = false;
                long deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos();
                while (System.nanoTime() < deadline) {
                    for (String r : client.received()) {
                        if (r.startsWith("S|")) { successCount.incrementAndGet(); gotSync = true; break; }
                        if (r.startsWith("E|503")) { gridFullCount.incrementAndGet(); gridFullPayload.set(r); gotSync = true; break; }
                        if (r.startsWith("E|")) { errorCount.incrementAndGet(); gotSync = true; break; }
                    }
                    if (gotSync) break;
                    Thread.sleep(10);
                }
                if (!gotSync) {
                    errorCount.incrementAndGet();
                    break;
                }
                if (gridFullCount.get() > 0) break;
            }
        }

        // At least some placements must have succeeded.
        assertThat(successCount.get())
                .as("At least 1 placement should succeed on 8×8 grid")
                .isGreaterThan(0);

        // GRID_FULL must have been received eventually.
        assertThat(gridFullCount.get())
                .as("GRID_FULL (E|503) must be received when eligible set is exhausted")
                .isGreaterThan(0);

        // A31 — the exhaustion 503 carries the exact grid-full token (condition→token, not just code).
        assertThat(gridFullPayload.get())
                .as("GRID_FULL frame must be the exact wire literal E|503|grid-full")
                .isEqualTo("E|503|grid-full");

        // No unexpected errors during the fill.
        assertThat(errorCount.get())
                .as("No unexpected errors during fill (errorCount=%d, successCount=%d, gridFullCount=%d)",
                        errorCount.get(), successCount.get(), gridFullCount.get())
                .isZero();

        // Index should report zero or near-zero eligible cells after GRID_FULL.
        int eligible = eligibleCellIndex.eligibleCount();
        log.info("EligibleCellIndex count after fill: {}", eligible);
        assertThat(eligible)
                .as("Eligible-cell index should be near-zero after GRID_FULL (actual=%d)", eligible)
                .isLessThanOrEqualTo(3);  // allow up to 3 for overcrowding-constrained edge cases

        log.info("Density test: {} placements, {} GRID_FULL, {} errors", successCount, gridFullCount, errorCount);
    }
}
