package com.paralife.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 14-06 Task 3: SUPPLEMENTAL full-stack smoke test.
 *
 * <p>Connects a real {@link StandardWebSocketClient} to the running
 * {@code /ws/world} endpoint, registers a bot, drives ~60 ticks via direct
 * {@link ApplicationEventPublisher#publishEvent(Object)}, and asserts that at
 * least one received {@code Perception} frame contains a {@link CellView} with
 * a non-zero {@code cellStatus} or {@code entityStatus} byte — proving the
 * env-effect perception wire-path is end-to-end operational.
 *
 * <p><b>cycle-6 MEDIUM #7 — shrunk world to 32x32</b> via
 * {@code @TestPropertySource} so one bot can reliably observe env status
 * within 60 ticks at aggressive peak lambdas. 256x256 (production default) was
 * too large to guarantee a 1-bot observation within the time budget.
 *
 * <p><b>Note on property key:</b> plan 14-06 PLAN.md originally specified
 * {@code paralife.grid.width=32} but the actual {@code @ConfigurationProperties}
 * prefix is {@code paralife.world} (see {@code GridConfig} at
 * {@code src/main/java/com/paralife/world/GridConfig.java:9}). Using
 * {@code paralife.world.width=32} here — the only working form. Documented
 * as a Rule 1 bug fix in the 14-06 SUMMARY.
 *
 * <p><b>Scope:</b> this is SUPPLEMENTAL to the roadmap-literal
 * {@link EnvironmentPhaseGateIntegrationTest}. This test guards the WebSocket
 * wire coverage only; it does NOT enforce full-pipeline population stability
 * or all four env-effect firing assertions.
 *
 * <p><b>No BotClient instrumentation</b> (cycle-4 action item #11) —
 * reuses the raw {@link StandardWebSocketClient} + {@link TextWebSocketHandler}
 * subclass + {@link BlockingQueue} pattern already in
 * {@link PerceptionActionIntegrationTest}.
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
        // NOTE: PLAN.md specified "paralife.grid.width=32" but the actual
        // GridConfig prefix is "paralife.world" — this is the only working key
        // (Rule 1 bug fix, documented in SUMMARY).
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocketSession session;

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
    void tearDown() {
        if (session != null && session.isOpen()) {
            try { session.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Capture handler that splits incoming WebSocket text messages by their
     * {@code type} field. Mirrors PerceptionActionIntegrationTest.MessageCapture
     * (cycle-4 action item #11 — no BotClient instrumentation needed).
     */
    private static class MessageCapture extends TextWebSocketHandler {
        private final ObjectMapper objectMapper = new ObjectMapper();
        final BlockingQueue<JsonNode> welcomes = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> registered = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> perceptions = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get("type").asText();
            switch (type) {
                case "welcome" -> welcomes.add(node);
                case "registered" -> registered.add(node);
                case "perception" -> perceptions.add(node);
                default -> {
                    // ignore tick / action_result / error for smoke-test scope
                }
            }
        }
    }

    @Test
    void perceptionFrameCarriesNonZeroStatusWithin60Ticks() throws Exception {
        MessageCapture capture = new MessageCapture();
        StandardWebSocketClient client = new StandardWebSocketClient();
        session = client.execute(capture, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/world")).get(5, TimeUnit.SECONDS);

        // Wait for welcome.
        JsonNode welcome = capture.welcomes.poll(5, TimeUnit.SECONDS);
        assertThat(welcome).as("welcome frame received").isNotNull();

        // Register a CATALYST bot via raw JSON.
        LinkedHashMap<String, Object> registerMap = new LinkedHashMap<>();
        registerMap.put("type", "register");
        registerMap.put("entityType", "CATALYST");
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(registerMap)));

        JsonNode regFrame = capture.registered.poll(5, TimeUnit.SECONDS);
        assertThat(regFrame).as("registered frame received").isNotNull();
        String entityId = regFrame.get("entityId").asText();

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

        // Drive ticks so PerceptionBroadcaster emits a new perception with the
        // rebuilt cellStatus cache. One tick is enough — but drive a handful in
        // case WebSocket delivery races with the tick loop.
        boolean nonZeroStatusSeen = false;
        int maxTicks = 10;
        for (long tick = 1; tick <= maxTicks && !nonZeroStatusSeen; tick++) {
            publisher.publishEvent(new TickEvent(tick));
            JsonNode frame;
            while ((frame = capture.perceptions.poll(200, TimeUnit.MILLISECONDS)) != null) {
                if (hasNonZeroStatus(frame)) {
                    nonZeroStatusSeen = true;
                    break;
                }
            }
        }

        // Grace-period drain — catch any in-flight frames.
        if (!nonZeroStatusSeen) {
            JsonNode frame;
            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline && !nonZeroStatusSeen) {
                frame = capture.perceptions.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null && hasNonZeroStatus(frame)) {
                    nonZeroStatusSeen = true;
                }
            }
        }

        // Diagnostic log — helps distinguish "events never fired" from
        // "events fired but perception never observed them".
        log.info("Smoke test diagnostics: toxinEvents={} mutagenInfections={} "
                        + "lightningStrikes={} composts={} perceptionFramesRemaining={}",
                environmentEngine.getToxinEventCount(),
                environmentEngine.getMutagenInfectionEventCount(),
                environmentEngine.getLightningStrikeEventCount(),
                environmentEngine.getCompostEventCount(),
                capture.perceptions.size());

        assertThat(nonZeroStatusSeen)
                .as("cycle-6 MEDIUM #7 wire-path smoke: after stamping toxin intensity 255 at "
                        + "(%d,%d) adjacent to bot at (%d,%d), perception frame should carry a "
                        + "non-zero cellStatus byte within %d ticks on %dx%d world",
                        toxicCell.x(), toxicCell.y(), botPos.x(), botPos.y(),
                        maxTicks, worldGrid.getWidth(), worldGrid.getHeight())
                .isTrue();
        log.info("Smoke test observed non-zero status on {}x{} world",
                worldGrid.getWidth(), worldGrid.getHeight());
    }

    private boolean hasNonZeroStatus(JsonNode perceptionFrame) {
        JsonNode neighbourhood = perceptionFrame.get("neighbourhood");
        if (neighbourhood == null || !neighbourhood.isArray()) return false;
        for (JsonNode row : neighbourhood) {
            if (!row.isArray()) continue;
            for (JsonNode cell : row) {
                JsonNode cs = cell.get("cellStatus");
                JsonNode es = cell.get("entityStatus");
                if (cs != null && cs.asInt() != 0) return true;
                if (es != null && es.asInt() != 0) return true;
            }
        }
        return false;
    }
}
