package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.admission.AdmissionGate;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.WorldGrid;
import java.net.URI;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * O6a/O6b/O6c + C1, with the /ws/world positive control CO-LOCATED (same Spring context,
 * same autowired counters). C1: the observer upgrade uses a BROWSER-EQUIVALENT offer (plain
 * permessage-deflate, WITHOUT server_no_context_takeover — a browser cannot send that param),
 * proving /ws/observer is exempt from the deflate-enforcement filter. Frames are parsed with
 * Jackson (not substring-matched), asserting the real wire contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=100",
        "paralife.tick.auto-start=true",   // ticks fire → observer still gets world frames
        // Freeze the world so entityCount/slot/registry deltas are deterministic: sim OFF stops
        // nutrient spawning + decay/death (SimulationEngine early-returns on !enabled), events OFF
        // stops env. Registration/placement is in the handler, independent of these — the /ws/world
        // control still places its bot. Without this, a probabilistic nutrient spawn between the
        // before/after capture would fail O6a (entityCount counts nutrients).
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=false",
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.observer.enabled=true",
        "paralife.observer.max-sessions=4"
})
class ObserverEndpointIntegrationTest {

    @LocalServerPort int port;
    @Autowired AdmissionGate admissionGate;
    @Autowired BotRegistry botRegistry;
    @Autowired WorldGrid worldGrid;
    @Autowired SpeciesSpawnCounter spawnCounter;

    private final ObjectMapper mapper = new ObjectMapper();
    private WebSocketClient client;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.stop();
    }

    @WebSocket
    public static class Capture {
        final CopyOnWriteArrayList<String> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;
        Capture(CountDownLatch latch) { this.latch = latch; }
        @OnWebSocketMessage
        public void onMessage(String msg) { frames.add(msg); latch.countDown(); }
    }

    /** Bot-side capture: decodes wire frames so we can await the SyncFrame. */
    @WebSocket
    public static class BotCapture {
        final CopyOnWriteArrayList<Frame> frames = new CopyOnWriteArrayList<>();
        @OnWebSocketMessage
        public void onMessage(String msg) {
            try { frames.add(PerceptionCodec.decode(msg)); } catch (Exception ignored) { }
        }
    }

    private Session connect(Object endpoint, String path, String extensions) throws Exception {
        client = new WebSocketClient();
        client.start();
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions(extensions);
        return client.connect(endpoint, URI.create("ws://localhost:" + port + path), req)
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void observerBrowserOffer_getsBootstrapThenWorld_mutatesNoGridSlotOrRegistry() throws Exception {
        int slotsBefore = admissionGate.reservedSlots();
        int botsBefore = botRegistry.size();
        int occupantsBefore = worldGrid.snapshot().entityCount();

        CountDownLatch got2 = new CountDownLatch(2); // bootstrap + ≥1 world frame
        Capture cap = new Capture(got2);
        // browser-equivalent offer: NO server_no_context_takeover (guards C1)
        Session session = connect(cap, "/ws/observer", "permessage-deflate");
        assertThat(session.isOpen()).as("browser-equivalent handshake succeeded (C1 exemption)").isTrue();
        assertThat(got2.await(5, TimeUnit.SECONDS)).as("bootstrap + a world frame arrived").isTrue();

        // Jackson-parse the real contract, not substrings
        JsonNode bootstrap = mapper.readTree(cap.frames.get(0));
        assertThat(bootstrap.get("type").asText()).as("bootstrap first").isEqualTo("bootstrap");
        assertThat(bootstrap.get("grid").get("width").asInt()).isEqualTo(16);
        JsonNode world = null;
        for (String f : cap.frames) {
            JsonNode n = mapper.readTree(f);
            if ("world".equals(n.get("type").asText())) { world = n; break; }
        }
        assertThat(world).as("a world frame followed").isNotNull();
        assertThat(world.has("entities") && world.has("populations")).isTrue();

        // O6a/O6b/O6c: observer created no grid occupant, consumed no slot, added no registry entry
        assertThat(worldGrid.snapshot().entityCount())
                .as("O6a: observer placed no entity on the grid").isEqualTo(occupantsBefore);
        assertThat(admissionGate.reservedSlots())
                .as("O6b: observer consumed no admission slot").isEqualTo(slotsBefore);
        assertThat(botRegistry.size())
                .as("O6c: observer added no BotRegistry entry").isEqualTo(botsBefore);

        session.close(1000, "done", Callback.NOOP);
    }

    @Test
    void worldRegistrationControl_movesSlotRegistryGridAndSpawnCounter() throws Exception {
        // POSITIVE CONTROL proving the O6 gates are live, not inert: a real /ws/world admission
        // moves every counter the observer test asserts unchanged. Also covers O4's admission
        // creation path (spawns[CATALYST] += 1). Bot offer includes server_no_context_takeover.
        int slotsBefore = admissionGate.reservedSlots();
        int botsBefore = botRegistry.size();
        int occupantsBefore = worldGrid.snapshot().entityCount();
        long catBefore = spawnCounter.get(ParticleType.CATALYST);

        BotCapture cap = new BotCapture();
        Session bot = connect(cap, "/ws/world", "permessage-deflate; server_no_context_takeover");
        bot.sendText(PerceptionCodec.encode(new Frame.RegisterFrame('C')), Callback.NOOP);

        // admission resolves synchronously on the register frame → await the SyncFrame
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cap.frames.stream().noneMatch(f -> f instanceof Frame.SyncFrame)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(cap.frames).anyMatch(f -> f instanceof Frame.SyncFrame);

        assertThat(admissionGate.reservedSlots() - slotsBefore).as("+1 admission slot").isEqualTo(1);
        assertThat(botRegistry.size() - botsBefore).as("+1 BotRegistry entry").isEqualTo(1);
        assertThat(worldGrid.snapshot().entityCount() - occupantsBefore).as("+1 grid occupant").isEqualTo(1);
        assertThat(spawnCounter.get(ParticleType.CATALYST) - catBefore)
                .as("O4 admission path: +1 committed CATALYST spawn").isEqualTo(1L);

        bot.close(1000, "done", Callback.NOOP);
    }
}
