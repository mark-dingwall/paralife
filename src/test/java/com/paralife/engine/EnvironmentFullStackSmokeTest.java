package com.paralife.engine;

import com.paralife.codec.CellEntry;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 14-06 Task 3: SUPPLEMENTAL full-stack smoke test.
 *
 * <p>Connects a real Jetty {@link WebSocketClient} to the running
 * {@code /ws/world} endpoint, registers a bot, drives ~60 ticks via direct
 * {@link ApplicationEventPublisher#publishEvent(Object)}, and asserts that
 * at least one received {@link Frame.TickFrame} carries a {@link CellEntry}
 * with a non-zero {@code envState} or {@code entityState} byte — proving the
 * env-effect perception wire-path is end-to-end operational.
 *
 * <p><b>Plan 15-11 migration.</b> Rewritten against the codec-native wire
 * protocol (plan 15-06):
 * <ul>
 *   <li>{@link org.springframework.web.socket.client.standard.StandardWebSocketClient}
 *       → Jetty {@link WebSocketClient} so the upgrade request can advertise
 *       {@code permessage-deflate; server_no_context_takeover} (D-33).</li>
 *   <li>Jackson JSON frame parsing → {@link PerceptionCodec#decode(String)}
 *       returning {@link Frame} subtypes.</li>
 *   <li>{@code Messages.Welcome/Registered/Perception} JSON types → no welcome
 *       (server stays quiet until {@code r|}); {@link Frame.SyncFrame} after
 *       register; {@link Frame.TickFrame} on each tick.</li>
 *   <li>{@code cellStatus/entityStatus} field access → {@link CellEntry#envState()}
 *       / {@link CellEntry#entityState()} {@link java.util.OptionalInt} accessors.</li>
 * </ul>
 *
 * <p><b>cycle-6 MEDIUM #7 — shrunk world to 12x12</b> via
 * {@code @TestPropertySource} so one bot can reliably observe env status
 * within 60 ticks at aggressive peak lambdas. 256x256 (production default) was
 * too large to guarantee a 1-bot observation within the time budget.
 *
 * <p><b>Note on property key:</b> plan 14-06 PLAN.md originally specified
 * {@code paralife.grid.width=12} but the actual {@code @ConfigurationProperties}
 * prefix is {@code paralife.world} (see {@code GridConfig} at
 * {@code src/main/java/com/paralife/world/GridConfig.java:9}). Using
 * {@code paralife.world.width=12} here — the only working form.
 *
 * <p><b>Scope:</b> this is SUPPLEMENTAL to the roadmap-literal
 * {@link EnvironmentPhaseGateIntegrationTest}. This test guards the WebSocket
 * wire coverage only; it does NOT enforce full-pipeline population stability
 * or all four env-effect firing assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // cycle-6 MEDIUM #7: shrunk world so 1 bot can reliably observe env
        // status within the tick window. 256x256 was flaky for 1-bot smoke in
        // cycle-5. Even 32x32 proved marginal — the bot's 5x5 vision only
        // covers 25/1024 cells and a single toxin/mutagen event rarely
        // overlaps random bot placement within 60 ticks. Shrunk further to
        // 12x12 (bot vision now covers ~17% of the grid and toxin paths
        // reliably diffuse across the bot's line-of-sight).
        "paralife.world.width=12",
        "paralife.world.height=12",
        // Aggressive peak lambdas to guarantee events fire within the window.
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.seasons.year-length-ticks=60",
        "paralife.simulation.events.lightning.peak-lambda=0.25",
        "paralife.simulation.events.toxin.peak-lambda=0.30",
        "paralife.simulation.events.mutagen.peak-lambda=0.25",
        "paralife.tick.auto-start=false"
})
class EnvironmentFullStackSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentFullStackSmokeTest.class);

    @LocalServerPort
    private int port;

    @Autowired private WorldGrid worldGrid;
    @Autowired private BotRegistry botRegistry;
    @Autowired private CompositeRegistry compositeRegistry;
    @Autowired private BuffRegistry buffRegistry;
    @Autowired private EnvironmentEngine environmentEngine;
    @Autowired private DeathFinalizer deathFinalizer;
    @Autowired private ApplicationEventPublisher publisher;

    private WebSocketClient client;
    private Session session;

    @BeforeEach
    void setUp() {
        worldGrid.clear();
        botRegistry.clear();
        compositeRegistry.clear();
        buffRegistry.clear();
        environmentEngine.resetForTest();
        deathFinalizer.resetCountForTest();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null && session.isOpen()) {
            try { session.close(1000, "test done", Callback.NOOP); } catch (Exception ignored) {}
        }
        if (client != null) {
            try { client.stop(); } catch (Exception ignored) {}
        }
    }

    /**
     * Capture endpoint that splits incoming {@link Frame} messages by subtype,
     * mirroring the pattern used in plan 15-11's migrated
     * {@link com.paralife.websocket.WebSocketIntegrationTest}.
     */
    @WebSocket
    public static class MessageCapture {
        final BlockingQueue<Frame.SyncFrame> syncs = new LinkedBlockingQueue<>();
        final BlockingQueue<Frame.TickFrame> ticks = new LinkedBlockingQueue<>();

        @OnWebSocketOpen
        public void onOpen(Session s) {
            // no-op — session reference retained by caller
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            try {
                Frame f = PerceptionCodec.decode(message);
                if (f instanceof Frame.SyncFrame sync) {
                    syncs.add(sync);
                } else if (f instanceof Frame.TickFrame tick) {
                    ticks.add(tick);
                }
                // ignore E, and echoes — out of scope for smoke test
            } catch (Exception ignored) {
                // decode failures are their own signal; we assert on the S+T
                // positive path. Swallow to keep the session open.
            }
        }
    }

    @Test
    void perceptionFrameCarriesNonZeroStatusWithin60Ticks() throws Exception {
        MessageCapture capture = new MessageCapture();
        client = new WebSocketClient();
        client.start();
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions("permessage-deflate; server_no_context_takeover");
        session = client.connect(capture,
                URI.create("ws://localhost:" + port + "/ws/world"), req)
                .get(5, TimeUnit.SECONDS);

        // Register a CATALYST bot via codec-native r| frame. Post-plan-15-06
        // the server stays silent until registration — no welcome frame.
        session.sendText(PerceptionCodec.encode(new Frame.RegisterFrame('C')), Callback.NOOP);

        Frame.SyncFrame sync = capture.syncs.poll(5, TimeUnit.SECONDS);
        assertThat(sync).as("S (sync) frame received after r|").isNotNull();
        String entityId = sync.entityId();

        // Discover the bot's position so we can plant a toxic cell inside its
        // 5x5 vision radius. This pattern gives the smoke test deterministic
        // coverage of the env-status wire path instead of relying on random
        // Poisson rolls to land a toxin path near a random-placed bot — which
        // proved flaky even at aggressive peak lambdas on 12x12 / 60 ticks
        // (cycle-5 HIGH, cycle-9 follow-up).
        String sessionId = botRegistry.getSessionForEntity(entityId).orElseThrow();
        Position botPos = botRegistry.getBySession(sessionId).orElseThrow().position();
        int w = worldGrid.getWidth(), h = worldGrid.getHeight();
        // Stamp maximum toxin intensity at (botPos.x()+1, botPos.y()) — inside
        // the 5x5 vision radius — and mark env damage so the status cache fills.
        Position toxicCell = new Position(Math.floorMod(botPos.x() + 1, w),
                Math.floorMod(botPos.y(), h));
        environmentEngine.stampToxinIntensityForTest(toxicCell, 255);

        // Drive ticks so PerceptionBroadcaster emits a new T frame with the
        // rebuilt cellStatus cache. One tick is enough — but drive a handful in
        // case WebSocket delivery races with the tick loop.
        boolean nonZeroStatusSeen = false;
        int maxTicks = 10;
        for (long tick = 1; tick <= maxTicks && !nonZeroStatusSeen; tick++) {
            publisher.publishEvent(new TickEvent(tick));
            Frame.TickFrame frame;
            while ((frame = capture.ticks.poll(200, TimeUnit.MILLISECONDS)) != null) {
                if (hasNonZeroStatus(frame)) {
                    nonZeroStatusSeen = true;
                    break;
                }
            }
        }

        // Grace-period drain — catch any in-flight frames.
        if (!nonZeroStatusSeen) {
            Frame.TickFrame frame;
            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline && !nonZeroStatusSeen) {
                frame = capture.ticks.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null && hasNonZeroStatus(frame)) {
                    nonZeroStatusSeen = true;
                }
            }
        }

        // Diagnostic log — helps distinguish "events never fired" from
        // "events fired but perception never observed them".
        log.info("Smoke test diagnostics: toxinEvents={} mutagenInfections={} "
                        + "lightningStrikes={} composts={} tickFramesRemaining={}",
                environmentEngine.getToxinEventCount(),
                environmentEngine.getMutagenInfectionEventCount(),
                environmentEngine.getLightningStrikeEventCount(),
                environmentEngine.getCompostEventCount(),
                capture.ticks.size());

        assertThat(nonZeroStatusSeen)
                .as("cycle-6 MEDIUM #7 wire-path smoke: after stamping toxin intensity 255 at "
                        + "(%d,%d) adjacent to bot at (%d,%d), T frame should carry a "
                        + "non-zero envState/entityState byte within %d ticks on %dx%d world",
                        toxicCell.x(), toxicCell.y(), botPos.x(), botPos.y(),
                        maxTicks, worldGrid.getWidth(), worldGrid.getHeight())
                .isTrue();
        log.info("Smoke test observed non-zero status on {}x{} world",
                worldGrid.getWidth(), worldGrid.getHeight());
    }

    /**
     * Scan the {@link Frame.TickFrame}'s cells for any non-zero env or entity
     * status byte — the wire-path signal that the env-effect projection
     * (layer 2 → wire bitmask per architecture three-layer model) is reaching
     * the client.
     */
    private boolean hasNonZeroStatus(Frame.TickFrame frame) {
        for (CellEntry ce : frame.cells()) {
            if (ce.envState().orElse(0) != 0) return true;
            if (ce.entityState().orElse(0) != 0) return true;
        }
        return false;
    }
}
