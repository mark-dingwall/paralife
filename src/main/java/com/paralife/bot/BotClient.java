// Bot package lives in src/main intentionally: bots will become standalone clients
// connecting over the network. Keeping them here during development avoids a premature
// module split while the protocol is still evolving.
package com.paralife.bot;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Paralife bot WebSocket client. Connects via Jetty 12's native
 * {@link WebSocketClient}, negotiates {@code permessage-deflate} and enforces
 * D-33 (fails fast if the server doesn't echo the extension), and drives a
 * pure-function {@link HeuristicBrain} over decoded {@link Frame.TickFrame}s.
 *
 * <p><b>Phase 15 (plan 15-09) refactor:</b>
 * <ul>
 *   <li>Transport swapped from Spring's {@code StandardWebSocketClient} (no
 *       public extension API) to Jetty-native {@link WebSocketClient}, which
 *       accepts {@code permessage-deflate; server_no_context_takeover} via
 *       {@link ClientUpgradeRequest#addExtensions(String...)}.</li>
 *   <li>Jackson removed. All wire I/O goes through
 *       {@link PerceptionCodec#encode} / {@link PerceptionCodec#decode}.</li>
 *   <li>{@link BotState} replaces the overloaded {@code currentType} char —
 *       species, embodiment, and compositeRole are orthogonal per SCHEMA §8.2.</li>
 *   <li>Respawn FSM: on receiving a {@code v<...>D} (died) event, the client
 *       does NOT close the session — it waits a randomised cooldown, then
 *       sends {@code r|<species>} again. Server resolves with {@code S} or
 *       {@code E|429}.</li>
 * </ul>
 */
public class BotClient {

    private static final Logger log = LoggerFactory.getLogger(BotClient.class);

    private final String serverUri;
    private final char species;                  // invariant across bot lifetime (C/M/S)
    private final HeuristicBrain brain;
    private final long respawnCooldownMs;
    private final long respawnJitterMs;
    private final Random rng;
    private final AtomicInteger actionCount = new AtomicInteger();
    private final AtomicInteger perceptionCount = new AtomicInteger();
    private final AtomicInteger syncCount = new AtomicInteger();
    private final AtomicInteger respawnCount = new AtomicInteger();
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch registeredLatch = new CountDownLatch(1);
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private final AtomicReference<BotState> state;

    private WebSocketClient client;
    private volatile Session session;
    private volatile String entityId;

    public BotClient(String serverUri, char species, HeuristicBrain brain) {
        this(serverUri, species, brain, 100L, 50L, new Random());
    }

    public BotClient(String serverUri, char species, HeuristicBrain brain,
                     long respawnCooldownMs, long respawnJitterMs) {
        this(serverUri, species, brain, respawnCooldownMs, respawnJitterMs,
                new Random());
    }

    public BotClient(String serverUri, char species, HeuristicBrain brain,
                     long respawnCooldownMs, long respawnJitterMs, Random rng) {
        if (species != 'C' && species != 'M' && species != 'S') {
            throw new IllegalArgumentException("species must be C/M/S: " + species);
        }
        this.serverUri = serverUri;
        this.species = species;
        this.brain = brain;
        this.respawnCooldownMs = respawnCooldownMs;
        this.respawnJitterMs = respawnJitterMs;
        this.rng = rng;
        this.state = new AtomicReference<>(BotState.initial(species));
    }

    /**
     * Connect to the server. After the upgrade resolves, enforce D-33
     * client-side by inspecting the response's {@code Sec-WebSocket-Extensions}
     * header. If the server omitted {@code permessage-deflate}, close the
     * session and throw {@link IllegalStateException}.
     *
     * <p>Immediately after the D-33 gate passes, sends the initial
     * {@code r|<species>} register frame.
     */
    public void connect() throws Exception {
        client = new WebSocketClient();
        client.start();

        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions("permessage-deflate; server_no_context_takeover");

        Endpoint endpoint = new Endpoint();
        Session connected = client.connect(endpoint, URI.create(serverUri), req)
                .get(10, TimeUnit.SECONDS);
        this.session = connected;

        // D-33 client-side enforcement — reject any upgrade response that lacks
        // permessage-deflate. Mitigation for threat T-15-02 (extension downgrade).
        String serverExt = connected.getUpgradeResponse().getHeader("Sec-WebSocket-Extensions");
        if (serverExt == null || !serverExt.contains("permessage-deflate")) {
            connected.close(1002, "Server did not negotiate permessage-deflate",
                    Callback.NOOP);
            throw new IllegalStateException(
                    "permessage-deflate not negotiated by server: " + serverExt);
        }

        connectedLatch.countDown();
        log.info("Bot connected: species={} uri={}", species, serverUri);

        // Send initial register frame immediately; server responds with S|<id>.
        sendFrame(new Frame.RegisterFrame(species));
    }

    /** Disconnect the bot and stop the underlying Jetty client. */
    public void disconnect() {
        try {
            Session s = this.session;
            if (s != null && s.isOpen()) {
                s.close(1000, "client disconnect", Callback.NOOP);
                log.info("Bot disconnected: entity={}", entityId);
            }
        } catch (Exception e) {
            log.warn("Error closing session: {}", e.getMessage());
        }
        try {
            if (client != null) {
                client.stop();
            }
        } catch (Exception e) {
            log.warn("Error stopping client: {}", e.getMessage());
        }
    }

    public boolean isConnected() {
        Session s = this.session;
        return s != null && s.isOpen();
    }

    public boolean isRegistered() {
        return entityId != null;
    }

    public String getEntityId() {
        return entityId;
    }

    /** The bot's current {@link BotState} — live projection of SCHEMA §8.2 c-block transitions. */
    public BotState state() {
        return state.get();
    }

    public boolean awaitConnected(long timeoutMs) throws InterruptedException {
        return connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean awaitRegistered(long timeoutMs) throws InterruptedException {
        return registeredLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Back-compat alias for {@link #awaitRegistered(long)}. Preserved so {@code
     * BotLauncher}'s virtual-thread bootstrap can keep its existing call site.
     */
    public boolean waitForRegistered(long timeout, TimeUnit unit) throws InterruptedException {
        return registeredLatch.await(timeout, unit);
    }

    public int getActionCount() {
        return actionCount.get();
    }

    public int getPerceptionCount() {
        return perceptionCount.get();
    }

    /**
     * Phase 15.2: count of successful re-registrations after a death event.
     * Zero on the initial sync; increments once per server S frame received
     * after a {@code vD} respawn cycle.
     */
    public int getRespawnCount() {
        return respawnCount.get();
    }

    /** Encode + send a frame. Silently no-ops if the session is not open. */
    private synchronized void sendFrame(Frame f) {
        Session s = this.session;
        if (s == null || !s.isOpen()) return;
        try {
            s.sendText(PerceptionCodec.encode(f), Callback.NOOP);
        } catch (Exception e) {
            log.warn("Failed to send frame: {}", e.getMessage());
        }
    }

    private void handlePayload(String payload) {
        Frame frame;
        try {
            frame = PerceptionCodec.decode(payload);
        } catch (Exception e) {
            log.warn("Failed to decode frame: {}", e.getMessage());
            return;
        }
        switch (frame) {
            case Frame.SyncFrame s -> onSync(s);
            case Frame.TickFrame t -> onTick(t);
            case Frame.ErrorFrame e -> onError(e);
            // Server never sends r or a; ignore defensively.
            case Frame.RegisterFrame ignored -> {}
            case Frame.ActionFrame ignored -> {}
        }
    }

    private void onSync(Frame.SyncFrame s) {
        entityId = s.entityId();
        alive.set(true);
        // On re-sync after respawn, reset BotState to a fresh SOLO of the
        // original species — any prior bonded/composite state is gone on death.
        state.set(BotState.initial(species));
        if (syncCount.getAndIncrement() == 0) {
            registeredLatch.countDown();
        } else {
            respawnCount.incrementAndGet();
        }
        log.info("Bot registered: entity={} species={}", entityId, species);
    }

    private void onTick(Frame.TickFrame t) {
        perceptionCount.incrementAndGet();

        // Apply state-change code FIRST — HeuristicBrain needs an up-to-date BotState.
        t.change().ifPresent(c -> state.updateAndGet(prev -> prev.withChangeCode(c.code())));

        // Death check: any v-block D event (SCHEMA §8.4 "Died") triggers respawn flow.
        boolean died = t.events().stream().anyMatch(ev -> ev.code() == 'D');
        if (died) {
            handleDeath();
            return;
        }

        // Minimal-form frames (passive composite members) carry no vision/effects/pool,
        // but the brain's null-return path already covers passive roles via BotState.
        Frame.ActionFrame decision = brain.decide(t, state.get(), rng);
        if (decision != null) {
            sendFrame(decision);
            actionCount.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("Bot {} tick {} → {}{}", entityId, t.tickId(),
                        decision.verb(), decision.arg().map(a -> "|" + a).orElse(""));
            }
        }
    }

    private void onError(Frame.ErrorFrame e) {
        log.warn("Server error {}: {}", e.code(), e.message().orElse(""));
        if (e.code() == 429) {
            // 429 is the server's back-pressure signal (respawn cap or population cap).
            // Disconnect instead of retrying and hammering the registration path.
            disconnect();
        }
    }

    /**
     * Respawn FSM. Keeps the session open; waits a randomised cooldown; sends
     * a fresh {@code r|<species>}. Server answers with {@code S|<newEntityId>}
     * or {@code E|429}.
     */
    private void handleDeath() {
        alive.set(false);
        entityId = null;
        long jitter = respawnJitterMs > 0
                ? rng.nextLong(respawnJitterMs)
                : 0L;
        long waitMs = respawnCooldownMs + jitter;
        CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS).execute(() -> {
            Session s = this.session;
            if (s != null && s.isOpen()) {
                sendFrame(new Frame.RegisterFrame(species));
                log.debug("Bot sent respawn r|{} after {}ms", species, waitMs);
            }
        });
    }

    /**
     * Jetty-annotated endpoint. Jetty 12 supports either {@link Session.Listener}
     * or the {@code @WebSocket}-annotated class style; we use annotations here
     * because the callbacks we care about are the simple text/open/close/error
     * set.
     */
    @WebSocket
    public class Endpoint {

        @OnWebSocketOpen
        public void onOpen(Session s) {
            // session reference is already captured by connect(); log only.
            log.debug("WS open: {}", s.getRemoteSocketAddress());
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            handlePayload(message);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            log.info("WS closed: {} {}", statusCode, reason);
        }

        @OnWebSocketError
        public void onError(Throwable cause) {
            log.warn("WS error: {}", cause.getMessage());
        }
    }
}
