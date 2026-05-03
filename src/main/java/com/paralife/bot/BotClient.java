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
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *
 * <p><b>Phase 18 (plan 18-01):</b> Added {@link BotIdentity} carriage via
 * WebSocket handshake headers ({@code X-Paralife-Source} /
 * {@code X-Paralife-Harness}). Headers are re-sent on <em>every</em>
 * {@link #connect()} invocation — this preserves attribution across
 * Phase 17 STALLED-pivot reconnects (RESEARCH.md Pitfall 1 / T-18-04).
 * The {@link BotClientOptions} record accepts identity without breaking the
 * existing 3/5/6-arg constructors (RESEARCH.md Pitfall 2).
 */
public class BotClient {

    private static final Logger log = LoggerFactory.getLogger(BotClient.class);

    private final String serverUri;
    private final char species;                  // invariant across bot lifetime (C/M/S)
    private final HeuristicBrain brain;
    private final long respawnCooldownMs;
    private final long respawnJitterMs;
    private final Random rng;
    /** Phase 18 D-06: harness identity carried in the WS handshake headers. Final field — re-used on every connect(). */
    private final BotIdentity identity;
    private final AtomicInteger actionCount = new AtomicInteger();
    private final AtomicInteger perceptionCount = new AtomicInteger();
    private final AtomicInteger syncCount = new AtomicInteger();
    private final AtomicInteger respawnCount = new AtomicInteger();
    // Phase 18 Plan 05 (Round 2 OpenCode HIGH): new monotonic counters for LoadHarness report.
    private final AtomicInteger e408ReconnectRequiredCount = new AtomicInteger();
    private final AtomicInteger syncsReceivedCount = new AtomicInteger();
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch registeredLatch = new CountDownLatch(1);
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private final AtomicReference<BotState> state;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    // Phase 18 Plan 04: close callback support (D-04 BotFleet liveCount tracking).
    // CopyOnWriteArrayList for safe iteration while new callbacks may be registered.
    private final CopyOnWriteArrayList<Runnable> closeCallbacks = new CopyOnWriteArrayList<>();

    /**
     * Round 2 Codex HIGH amendment: CAS gate so close callbacks fire EXACTLY ONCE per
     * BotClient lifetime. Without this, {@code disconnect()} from the owning thread and
     * Jetty's {@code @OnWebSocketClose} from the read thread can both call
     * {@code fireCloseCallbacks()}, decrementing {@code BotFleet.liveCount} twice and
     * causing it to go negative.
     */
    private final AtomicBoolean closedFired = new AtomicBoolean(false);

    private WebSocketClient client;
    private volatile Session session;
    private volatile String entityId;
    /** Phase 17: server-issued opaque resume token; sent on reconnect after STALLED to re-bind to the same entity. */
    private volatile String resumeToken;

    /**
     * Primary constructor (Phase 18). Accepts a {@link BotClientOptions} record so
     * identity can be supplied without breaking existing 3/5/6-arg call sites
     * (RESEARCH.md Pitfall 2).
     */
    public BotClient(BotClientOptions opts) {
        char sp = opts.species();
        if (sp != 'C' && sp != 'M' && sp != 'S') {
            throw new IllegalArgumentException("species must be C/M/S: " + sp);
        }
        this.serverUri = opts.serverUri();
        this.species = sp;
        this.brain = opts.brain();
        this.respawnCooldownMs = opts.respawnCooldownMs();
        this.respawnJitterMs = opts.respawnJitterMs();
        this.rng = opts.rng();
        this.identity = opts.identity();
        this.state = new AtomicReference<>(BotState.initial(sp));
    }

    /** Back-compat 3-arg constructor. Defaults to {@link BotIdentity#unknown()}. */
    public BotClient(String serverUri, char species, HeuristicBrain brain) {
        this(BotClientOptions.defaults(serverUri, species, brain));
    }

    /** Back-compat 5-arg constructor. Defaults to {@link BotIdentity#unknown()}. */
    public BotClient(String serverUri, char species, HeuristicBrain brain,
                     long respawnCooldownMs, long respawnJitterMs) {
        this(new BotClientOptions(serverUri, species, brain, respawnCooldownMs,
                respawnJitterMs, new Random(), BotIdentity.unknown()));
    }

    /** Back-compat 6-arg constructor. Defaults to {@link BotIdentity#unknown()}. */
    public BotClient(String serverUri, char species, HeuristicBrain brain,
                     long respawnCooldownMs, long respawnJitterMs, Random rng) {
        this(new BotClientOptions(serverUri, species, brain, respawnCooldownMs,
                respawnJitterMs, rng, BotIdentity.unknown()));
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
        // Phase 17: reuse the WebSocketClient across reconnects. Originally a fresh
        // client+pool was created per connect(); STALLED-pivot reconnects then leaked
        // selector + executor + scheduler thread pools (200+ threads after 100 reconnects).
        // Stopping the old client per-reconnect was even worse — `stop()` blocks during
        // the very thundering-herd conditions a stall produces. Lazy-init once; reuse
        // for subsequent connect() calls.
        if (client == null) {
            client = new WebSocketClient();
            client.start();
        }

        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions("permessage-deflate; server_no_context_takeover");

        // Phase 18 D-06: harness identity carriage. Headers re-sent on EVERY connect()
        // invocation — this is what preserves attribution across STALLED-pivot reconnects
        // (RESEARCH.md Pitfall 1 / T-18-04). identity is a final field bound at construction time.
        req.setHeader("X-Paralife-Source", identity.source());
        identity.harnessId().ifPresent(id -> req.setHeader("X-Paralife-Harness", id));

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
        // Phase 17: if a resume token is held (STALLED reconnect), include it.
        sendInitialRegister();
    }

    /** Disconnect the bot and stop the underlying Jetty client. */
    public void disconnect() {
        shutdown.set(true);
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
                client = null;
            }
        } catch (Exception e) {
            log.warn("Error stopping client: {}", e.getMessage());
        }
        // Phase 18 D-04: fire close callbacks AFTER the session is closed so callers
        // (e.g. BotFleet.liveCount decrement) see consistent state. CAS-guarded so
        // concurrent calls from disconnect() + Jetty @OnWebSocketClose fire exactly once.
        fireCloseCallbacks();
    }

    public boolean isConnected() {
        Session s = this.session;
        return s != null && s.isOpen();
    }

    /** Phase 18 D-06: returns the harness identity carried in the handshake headers. */
    public BotIdentity identity() {
        return identity;
    }

    /**
     * Register a callback to be invoked when this BotClient closes (either via explicit
     * {@link #disconnect()} or remote close from the server). Phase 18 D-04: {@link BotFleet}
     * uses this hook to track {@code liveCount} for {@code BotFleet.currentRegistered()}.
     *
     * <p><b>Fire-once contract (Round 2 Codex HIGH):</b> regardless of how many close paths
     * trigger ({@code disconnect()} from the owning thread + Jetty {@code @OnWebSocketClose}
     * from the read thread), each registered Runnable runs at most once per BotClient lifetime.
     * The gate is an {@link AtomicBoolean} compare-and-set; only the first close path wins.
     *
     * <p><b>Note:</b> STALLED-pivot reconnects (Phase 17 D-13) are NOT reported via this hook —
     * the BotClient internal reconnect loop handles those transparently.
     */
    public void onClose(Runnable r) {
        closeCallbacks.add(Objects.requireNonNull(r, "close callback must not be null"));
    }

    /**
     * CAS-guarded fire — runs all registered close callbacks EXACTLY ONCE per BotClient
     * lifetime, regardless of how many close paths call this method concurrently.
     */
    private void fireCloseCallbacks() {
        if (!closedFired.compareAndSet(false, true)) {
            return; // another close path already fired the callbacks
        }
        for (Runnable r : closeCallbacks) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("close callback failed: {}", e.getMessage());
            }
        }
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

    /**
     * Phase 18 Plan 05 (Round 2 OpenCode HIGH): monotonic count of E|408|reconnect-required
     * frames received from the server. Incremented in the STALLED-pivot path before
     * {@link #handleStalled()} is called.
     */
    public int getE408ReconnectRequiredCount() {
        return e408ReconnectRequiredCount.get();
    }

    /**
     * Phase 18 Plan 05 (Round 2 OpenCode HIGH): monotonic count of S|... (sync) frames
     * received. Incremented on every {@link Frame.SyncFrame} — initial registration AND
     * each respawn re-sync.
     */
    public int getSyncsReceivedCount() {
        return syncsReceivedCount.get();
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
        // Phase 17: store the resume token when the server issues one (fresh or re-bind).
        // Overwrites any stale token — consumed-once semantics on successful re-bind.
        s.resumeToken().ifPresent(t -> this.resumeToken = t);
        alive.set(true);
        // On re-sync after respawn, reset BotState to a fresh SOLO of the
        // original species — any prior bonded/composite state is gone on death.
        state.set(BotState.initial(species));
        // Phase 18 Plan 05 (Round 2 OpenCode HIGH): increment syncsReceivedCount on every
        // S|... frame — both initial registration AND each respawn re-sync.
        syncsReceivedCount.incrementAndGet();
        if (syncCount.getAndIncrement() == 0) {
            registeredLatch.countDown();
        } else {
            respawnCount.incrementAndGet();
        }
        log.info("Bot registered: entity={} species={} hasResumeToken={}",
                entityId, species, resumeToken != null);
    }

    private void onTick(Frame.TickFrame t) {
        perceptionCount.incrementAndGet();

        // Apply state-change code FIRST — HeuristicBrain needs an up-to-date BotState.
        t.change().ifPresent(c -> state.updateAndGet(prev -> prev.withChangeCode(c.code())));

        // Phase 19.5 E3: terminal-frame check. v|D ("Died") and v|B
        // ("absorBed" — prey consumed at bond formation) both mean the
        // entity is gone; the respawn FSM is identical, only the log
        // reason differs.
        for (var ev : t.events()) {
            if (ev.code() == 'D') { handleEntityTerminated("died"); return; }
            if (ev.code() == 'B') { handleEntityTerminated("absorbed"); return; }
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
        String msg = e.message().orElse("");
        log.warn("Server error {}: {}", e.code(), msg);
        if (e.code() == 408 && "reconnect-required".equals(msg)) {
            // Phase 18 Plan 05 (Round 2 OpenCode HIGH): increment before handleStalled so the
            // monotonic counter is visible to LoadHarness even if the bot never reconnects.
            e408ReconnectRequiredCount.incrementAndGet();
            // Phase 17 STALLED-pivot: server is closing this WS; reconnect with token to rebind same entity.
            handleStalled();
            return;
        }
        if (e.code() == 429) {
            // 429 is the server's back-pressure signal (respawn cap or population cap).
            // Disconnect instead of retrying and hammering the registration path.
            disconnect();
        }
    }

    /**
     * Phase 17 STALLED-pivot handler. Called when the server sends {@code E|408|reconnect-required}.
     * The server is closing (or has closed) the WebSocket; the entity is held on the grid
     * for {@code graceWindowTicks}. We mark ourselves not-alive and let the {@code onClose}
     * hook trigger reconnect with the stored resume token.
     *
     * <p>Orthogonal to {@link #handleDeath}: death stays on the same WS with a fresh entity;
     * STALLED requires a new WS and re-binds the same entity via the resume token.
     */
    private void handleStalled() {
        alive.set(false);
        // Do NOT null entityId — server preserves the entity during the grace window.
        // Keep resumeToken — it will be sent on reconnect via sendInitialRegister().
        log.info("Bot STALLED: entity={} species={} hasResumeToken={}", entityId, species, resumeToken != null);
        // The server will close the WS. onClose() in Endpoint triggers reconnect when resumeToken != null.
    }

    /**
     * Sends the initial register frame after a WS connection is established.
     * If a resume token is held (STALLED reconnect path), includes it so the server
     * can re-bind the entity. Otherwise sends a plain fresh-registration frame.
     *
     * <p>Death-pivot ({@link #handleDeath}) does NOT use this helper — it always
     * sends a no-token {@link Frame.RegisterFrame} for a fresh entity on the same WS.
     */
    private void sendInitialRegister() {
        String token = this.resumeToken;
        if (token != null) {
            sendFrame(new Frame.RegisterFrame(species, Optional.of(token)));
            log.info("Reconnect with resume-token (entityId={} preserved if grace not expired)", entityId);
        } else {
            sendFrame(new Frame.RegisterFrame(species));
        }
    }

    /**
     * Re-enters the connect cycle after a STALLED WS close.
     * Called from the {@code onClose} hook when a resume token is held.
     */
    private void reconnect() {
        try {
            connect();
        } catch (Exception e) {
            log.warn("Reconnect failed: {}", e.getMessage());
        }
    }

    /**
     * Respawn FSM. Keeps the session open; waits a randomised cooldown; sends
     * a fresh {@code r|<species>}. Server answers with {@code S|<newEntityId>}
     * or {@code E|429}.
     */
    private void handleEntityTerminated(String reason) {
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
                log.debug("Bot sent respawn r|{} after {}ms (reason={})", species, waitMs, reason);
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
            log.info("WS closed: status={} reason={}", statusCode, reason);
            // Phase 18 D-04: fire close callbacks BEFORE reconnect logic so callers
            // (e.g. BotFleet.liveCount decrement) see the close event.
            // CAS gate in fireCloseCallbacks() ensures this fires EXACTLY ONCE even if
            // disconnect() and this Jetty path both trigger (Round 2 Codex HIGH).
            fireCloseCallbacks();
            if (resumeToken != null && !shutdown.get()) {
                // Phase 17 STALLED-pivot: reconnect on a fresh WS to attempt re-bind within grace window.
                // Jittered 100–300ms — anti-thundering-herd when many sessions stall together
                // (e.g., GC pause hits the server, cascade of overflows, all clients reconnect at once).
                long delayMs = 100L + rng.nextLong(200L);
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS).execute(BotClient.this::reconnect);
            }
        }

        @OnWebSocketError
        public void onError(Throwable cause) {
            log.warn("WS error: {}", cause.getMessage());
        }
    }
}
