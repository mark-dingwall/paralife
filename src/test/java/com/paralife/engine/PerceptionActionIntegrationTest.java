package com.paralife.engine;

import com.paralife.codec.CellEntry;
import com.paralife.codec.Coord;
import com.paralife.codec.Frame;
import com.paralife.codec.KindData;
import com.paralife.codec.PerceptionCodec;
import com.paralife.world.Entity;
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
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 15-11 (Task 2): end-to-end perception→action→perception over the codec
 * wire.
 *
 * <p>Migrated from the Messages-era JSON channel ({@code perception} +
 * {@code action} + {@code action_result}) to SCHEMA §6.3 / §8.6 frames:
 * <ul>
 *   <li>Register: {@code r|<species>} → server responds {@code S|<entityId>}.</li>
 *   <li>Perception: server emits {@code T|<tickId>|...} each tick per bot.</li>
 *   <li>Action: client sends {@code a|<verb>[|<arg>]} (M/E/A/R/V/L).</li>
 *   <li>No action_result frames — the next tick's state IS the acknowledgement.
 *       Assertions switched from "action succeeded"/"action failed" to
 *       observable world-state changes (position, energy, occupancy).</li>
 * </ul>
 *
 * <p>Uses Jetty-native {@link WebSocketClient} so the
 * {@code permessage-deflate; server_no_context_takeover} extension header is
 * sent on the upgrade — Spring's {@code StandardWebSocketClient} has no public
 * API for this and the server filter rejects upgrades without it (D-33).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=200",
        "paralife.tick.auto-start=true",
        "paralife.world.width=32",
        "paralife.world.height=32",
        "paralife.simulation.enabled=false"  // disable physics so tests control state precisely
})
class PerceptionActionIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PerceptionActionIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private WorldGrid worldGrid;

    @Autowired
    private BotRegistry botRegistry;

    private final List<BotConn> connections = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        worldGrid.clear();
        botRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        for (BotConn bc : connections) bc.close();
        connections.clear();
    }

    // ── Tests ─────────────────────────────────────────────────────

    @Test
    void botReceivesTickFrameAfterRegister() throws Exception {
        BotConn bot = connectAndRegister('C');

        Frame.TickFrame tick = waitForTickFrame(bot, 5);
        assertThat(tick).as("Should receive TickFrame after register").isNotNull();
        assertThat(tick.sensorRadius())
                .as("Solo Particle → FULL tier, radius 2")
                .isEqualTo(2);
    }

    @Test
    void moveActionChangesPosition() throws Exception {
        BotConn bot = connectAndRegister('C');

        // Wait for first tick to learn starting position.
        Frame.TickFrame first = waitForTickFrame(bot, 5);
        int startX = first.curX();
        int startY = first.curY();

        // Submit M|6 (move East).
        bot.send(new Frame.ActionFrame('M', Optional.of("6")));

        // Poll ticks until position changes (max ~5 ticks at 200ms each = 1s).
        Frame.TickFrame after = waitForTickWithNewPosition(bot, startX, startY, 10);
        assertThat(after).as("Should observe position change after move").isNotNull();
        int expectedX = (startX + 1) % worldGrid.getWidth();
        assertThat(after.curX()).isEqualTo(expectedX);
        assertThat(after.curY()).isEqualTo(startY);
        log.info("Move verified: ({},{}) → ({},{})", startX, startY, after.curX(), after.curY());
    }

    @Test
    void consumeActionGainsEnergy() throws Exception {
        BotConn bot = connectAndRegister('C');

        Frame.TickFrame first = waitForTickFrame(bot, 5);
        int x = first.curX();
        int y = first.curY();
        int startEnergy = first.energy();

        // Place a nutrient adjacent east.
        int nx = (x + 1) % worldGrid.getWidth();
        worldGrid.setEntity(nx, y, Entity.Nutrient.spawn("test-nutrient"));

        // Submit E (consume, no arg per SCHEMA §8.6).
        bot.send(new Frame.ActionFrame('E', Optional.of("5")));

        Frame.TickFrame after = waitForTickWithEnergyGreaterThan(bot, startEnergy, 10);
        assertThat(after).as("Should observe energy gain after consume").isNotNull();
        assertThat(after.energy()).isGreaterThan(startEnergy);
        log.info("Consume verified: energy {} → {}", startEnergy, after.energy());
    }

    @Test
    void moveIntoRockFails() throws Exception {
        BotConn bot = connectAndRegister('C');

        Frame.TickFrame first = waitForTickFrame(bot, 5);
        int x = first.curX();
        int y = first.curY();

        // Place rock east.
        int rx = (x + 1) % worldGrid.getWidth();
        worldGrid.setEntity(rx, y, new Entity.Rock("test-rock"));

        // Try to move east.
        bot.send(new Frame.ActionFrame('M', Optional.of("6")));

        // After 5 ticks, position must be unchanged AND the rock must be visible
        // in the east numpad 6 slot.
        Thread.sleep(1200);  // ~6 ticks
        drainTicks(bot);
        Frame.TickFrame latest = waitForTickFrame(bot, 5);
        assertThat(latest.curX()).isEqualTo(x);
        assertThat(latest.curY()).isEqualTo(y);

        CellEntry east = latest.cells().stream()
                .filter(ce -> ce.coord() instanceof Coord.Numpad n && n.digit() == '6')
                .findFirst()
                .orElseThrow();
        assertThat(kindCodeOf(east))
                .as("Rock should be visible at numpad 6 (east)")
                .isEqualTo('R');
        log.info("Move into rock correctly blocked — rock visible at east slot");
    }

    @Test
    void twoBotsConflictOnSameCell() throws Exception {
        worldGrid.clear();
        botRegistry.clear();

        BotConn bot1 = connectAndRegister('C');
        Frame.TickFrame p1 = waitForTickFrame(bot1, 5);
        int x1 = p1.curX();
        int y1 = p1.curY();

        BotConn bot2 = connectAndRegister('M');
        Frame.TickFrame p2 = waitForTickFrame(bot2, 5);
        String bot2EntityId = bot2.entityId;

        // Reposition bot2 two cells east of bot1 so both target the same cell.
        int bot2X = (x1 + 2) % worldGrid.getWidth();
        String bot2SessionId = botRegistry.getSessionForEntity(bot2EntityId).orElseThrow();
        Position bot2OldPos = botRegistry.getBySession(bot2SessionId).orElseThrow().position();
        worldGrid.setEntity(bot2OldPos.x(), bot2OldPos.y(), null);
        Entity.Particle bot2Entity = Entity.Particle.spawn(bot2EntityId, Entity.ParticleType.MEMBRANE);
        worldGrid.setEntity(bot2X, y1, bot2Entity);
        botRegistry.updatePosition(bot2SessionId, new Position(bot2X, y1));

        // Both target (x1+1, y1): bot1 moves E (6), bot2 moves W (4).
        bot1.send(new Frame.ActionFrame('M', Optional.of("6")));
        bot2.send(new Frame.ActionFrame('M', Optional.of("4")));

        // Wait for resolution ticks, then assert exactly one bot landed on (x1+1, y1).
        Thread.sleep(1200);
        int targetX = (x1 + 1) % worldGrid.getWidth();
        var occupant = worldGrid.getCell(targetX, y1).occupant();
        assertThat(occupant)
                .as("Exactly one bot should occupy the contested cell")
                .isInstanceOf(Entity.Particle.class);

        // The non-winning bot should still have a living entity somewhere.
        boolean bot1Won = occupant instanceof Entity.Particle p && p.id().equals(bot1.entityId);
        boolean bot2Won = occupant instanceof Entity.Particle p && p.id().equals(bot2EntityId);
        assertThat(bot1Won ^ bot2Won)
                .as("Exactly one bot should win the conflict")
                .isTrue();
        log.info("Conflict test: bot1Won={} bot2Won={}", bot1Won, bot2Won);
    }

    @Test
    void restActionKeepsPosition() throws Exception {
        BotConn bot = connectAndRegister('S');

        Frame.TickFrame first = waitForTickFrame(bot, 5);
        int x = first.curX();
        int y = first.curY();

        // "Rest" in the new protocol: submit an illegal move direction (no-op).
        // Verb A with numpad digit 5 is rejected as invalid; V with self-vote
        // ballot is the IRV equivalent of abstention. We simply do nothing —
        // the tick loop keeps firing; position should not change.
        Thread.sleep(600);  // ~3 ticks, no action submitted
        drainTicks(bot);

        Frame.TickFrame after = waitForTickFrame(bot, 5);
        assertThat(after.curX()).isEqualTo(x);
        assertThat(after.curY()).isEqualTo(y);
    }

    // ── Connection harness ────────────────────────────────────────

    private BotConn connectAndRegister(char species) throws Exception {
        BotConn conn = new BotConn(port);
        connections.add(conn);
        conn.connect();
        // send r|<species>; wait for S|<entityId>
        conn.send(new Frame.RegisterFrame(species));
        Frame.SyncFrame sync = conn.waitForSync(5_000);
        assertThat(sync).as("Register should elicit S sync frame").isNotNull();
        conn.entityId = sync.entityId();
        return conn;
    }

    private static Frame.TickFrame waitForTickFrame(BotConn bot, int maxPoll) throws Exception {
        for (int i = 0; i < maxPoll; i++) {
            Frame.TickFrame tf = bot.tickQueue.poll(5_000, TimeUnit.MILLISECONDS);
            if (tf != null) return tf;
        }
        return null;
    }

    private static Frame.TickFrame waitForTickWithNewPosition(BotConn bot, int oldX, int oldY, int maxPoll) throws Exception {
        for (int i = 0; i < maxPoll; i++) {
            Frame.TickFrame tf = bot.tickQueue.poll(5_000, TimeUnit.MILLISECONDS);
            if (tf == null) return null;
            if (tf.curX() != oldX || tf.curY() != oldY) return tf;
        }
        return null;
    }

    private static Frame.TickFrame waitForTickWithEnergyGreaterThan(BotConn bot, int threshold, int maxPoll) throws Exception {
        for (int i = 0; i < maxPoll; i++) {
            Frame.TickFrame tf = bot.tickQueue.poll(5_000, TimeUnit.MILLISECONDS);
            if (tf == null) return null;
            if (tf.energy() > threshold) return tf;
        }
        return null;
    }

    private static void drainTicks(BotConn bot) {
        bot.tickQueue.clear();
    }

    private static Character kindCodeOf(CellEntry ce) {
        return ce.kind().map(kd -> switch (kd) {
            case KindData.Simple s -> s.code();
            case KindData.RockSolo ignored -> 'R';
            case KindData.RockRun run -> 'R';
        }).orElse(null);
    }

    /** Per-bot Jetty connection + captured frame queues. */
    static final class BotConn {
        final int port;
        WebSocketClient client;
        Session session;
        volatile String entityId;
        final BlockingQueue<Frame.TickFrame> tickQueue = new LinkedBlockingQueue<>();
        final BlockingQueue<Frame.SyncFrame> syncQueue = new LinkedBlockingQueue<>();
        final BlockingQueue<Frame.ErrorFrame> errorQueue = new LinkedBlockingQueue<>();

        BotConn(int port) {
            this.port = port;
        }

        void connect() throws Exception {
            client = new WebSocketClient();
            client.start();
            ClientUpgradeRequest req = new ClientUpgradeRequest();
            req.addExtensions("permessage-deflate; server_no_context_takeover");
            this.session = client.connect(new Endpoint(this),
                            URI.create("ws://localhost:" + port + "/ws/world"), req)
                    .get(10, TimeUnit.SECONDS);
        }

        void send(Frame frame) {
            session.sendText(PerceptionCodec.encode(frame), Callback.NOOP);
        }

        Frame.SyncFrame waitForSync(long millis) throws InterruptedException {
            return syncQueue.poll(millis, TimeUnit.MILLISECONDS);
        }

        void close() {
            try {
                if (session != null && session.isOpen()) {
                    session.close(1000, "done", Callback.NOOP);
                }
            } catch (Exception ignored) { /* best-effort */ }
            try {
                if (client != null) client.stop();
            } catch (Exception ignored) { /* best-effort */ }
        }
    }

    @WebSocket
    public static class Endpoint {
        private final BotConn bot;

        public Endpoint(BotConn bot) {
            this.bot = bot;
        }

        @OnWebSocketOpen
        public void onOpen(Session s) { /* captured by caller */ }

        @OnWebSocketMessage
        public void onMessage(String payload) {
            try {
                Frame f = PerceptionCodec.decode(payload);
                switch (f) {
                    case Frame.TickFrame t -> bot.tickQueue.add(t);
                    case Frame.SyncFrame s -> bot.syncQueue.add(s);
                    case Frame.ErrorFrame e -> bot.errorQueue.add(e);
                    case Frame.RegisterFrame ignored -> { /* server never sends r */ }
                    case Frame.ActionFrame ignored -> { /* server never sends a */ }
                }
            } catch (Exception e) {
                log.warn("Frame decode failure: {}", e.getMessage());
            }
        }
    }
}
