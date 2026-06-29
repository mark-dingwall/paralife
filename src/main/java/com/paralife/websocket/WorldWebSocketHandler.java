package com.paralife.websocket;

import com.paralife.admission.AdmissionConfig;
import com.paralife.admission.AdmissionGate;
import com.paralife.admission.AdmissionMetrics;
import com.paralife.admission.AdmissionResult;
import com.paralife.admission.AttributionSanitizer;
import com.paralife.admission.AttributionTagger;
import com.paralife.admission.OutboundSender;
import com.paralife.admission.RejectionToken;
import com.paralife.admission.ResumeTokenRegistry;
import com.paralife.bot.BotIdentity;
import com.paralife.codec.CodecException;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.ActionResolver;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.DeathCleanupHooks;
import com.paralife.engine.EligibleCellIndex;
import com.paralife.engine.EntityLifecycleListener;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.LiveEntityRegistry;
import com.paralife.engine.MetabolicProfile;
import com.paralife.engine.SpawnConfig;
import com.paralife.engine.TickEngine;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Phase 17 refactor — codec-driven WebSocket handler with full Wave-2 wiring.
 *
 * <p>All admission decisions are delegated to {@link AdmissionGate#evaluate}. Free-text
 * rejection messages are replaced by D-07 {@link RejectionToken} constants. The STALLED FSM
 * state ({@link #ATTR_STALL_TICK}) is managed here; outbound I/O goes through
 * {@link OutboundSender}; resume tokens are issued and tracked via {@link ResumeTokenRegistry}.
 *
 * <p><b>Session FSM (extended, Phase 17):</b>
 * <ul>
 *   <li><b>Unregistered</b> — no {@code entityId} attr. Only {@code r|} accepted.</li>
 *   <li><b>Alive</b> — {@code entityId} non-null. Action frames queued. Second {@code r|}
 *       → 409 already-registered via AdmissionGate.</li>
 *   <li><b>Dead (respawn pending)</b> — {@code entityId} cleared, {@code entityType} intact.
 *       Next {@code r|} is a respawn (Phase 15.2 death-pivot via {@link #markDead}).</li>
 *   <li><b>STALLED</b> — {@link #ATTR_STALL_TICK} set. WS closed after {@code E|408|reconnect-required}.
 *       Entity held on grid; client reconnects with resume token via new WS session.</li>
 * </ul>
 *
 * <p><b>STALLED-aware close (consensus HIGH fix):</b> when {@code afterConnectionClosed} fires
 * on a STALLED session, the entity is preserved on the grid for {@link ResumeTokenRegistry} to
 * reap via grace-expiry sweep. {@link #cleanupBot} is NOT called on stalled close.
 *
 * <p><b>Idempotent {@link #markStalled}:</b> guarded by {@link #ATTR_STALL_TICK} presence;
 * the overflow callback fires at most once per attach lifecycle (Plan 06 fire-once guard).
 *
 * <p><b>Close-then-best-effort-OOB (Phase 19.1 D-07):</b> {@link #markStalled} invokes the
 * close-aware {@link OutboundSender#detachSession(WebSocketSession, CloseStatus)} overload with
 * {@code SERVICE_RESTARTED}; the wire-level close is the reconnect signal. The 408 OOB frame
 * that follows is best-effort — short-circuited by the {@code isOpen()} guard in
 * {@link #sendOutOfBand} once the transport has been closed.
 */
@Component
public class WorldWebSocketHandler extends TextWebSocketHandler implements EntityLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(WorldWebSocketHandler.class);

    // ── FSM session attribute keys ───────────────────────────────────────────
    private static final String ATTR_ENTITY_ID     = "entityId";
    private static final String ATTR_ENTITY_TYPE   = "entityType";
    private static final String ATTR_RESPAWN_COUNT = "respawnCount";
    /** Phase 17: set to the tick number when session transitions to STALLED. */
    private static final String ATTR_STALL_TICK    = "stallTick";
    /** Phase 17: local cache of the ACTIVE resume token so markStalled can convertToStalled. */
    private static final String ATTR_RESUME_TOKEN  = "resumeToken";

    /**
     * Phase 19 SCALE-06 (REVIEWS L4 / LOW-12): max lost-race retries before
     * declaring GRID_FULL. A race occurs when {@code eligibleCellIndex.sample()}
     * returns a cell but a concurrent registration wins the {@code trySetEntity}
     * lock first. Each retry increments {@code paralife.placement.lost-race.total}.
     */
    private static final int LOST_RACE_MAX_RETRIES = 3;

    // ── Wave-2 wired beans ───────────────────────────────────────────────────
    private final AdmissionGate admissionGate;
    private final OutboundSender outboundSender;
    private final ResumeTokenRegistry resumeTokenRegistry;
    private final AdmissionConfig admissionConfig;
    private final AdmissionMetrics admissionMetrics;

    // ── Pre-existing beans ───────────────────────────────────────────────────
    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final TickEngine tickEngine;
    private final BotRegistry botRegistry;
    private final ActionResolver actionResolver;
    private final MetabolicProfile metabolicProfile;
    private final SpawnConfig spawnConfig;

    /**
     * Phase 17 Option B: external respawn-count snapshot at stall time, keyed by entityId.
     * Cleared on cleanupByEntityId or successful rebind. Avoids retroactively widening Plan 05 API.
     */
    private final ConcurrentHashMap<String, Integer> respawnCountAtStall = new ConcurrentHashMap<>();

    /**
     * Phase 16 Plan 01: seeded placement RNG. Non-final so {@link #resetSeed()} can reassign
     * it between test runs. Bound from {@link SpawnConfig#seed()} — null = unseeded (production).
     */
    private Random spawnRng;

    /**
     * Phase 19 SCALE-06 (D-01): O(1) eligible-cell index. Replaces the 50-retry
     * random scan at the {@code r|} placement path.
     *
     * <p>REVIEWS MED-7: the primary {@code @Autowired} constructor enforces non-null
     * via {@link Objects#requireNonNull}. Back-compat convenience constructors (which
     * do not exercise the placement path) explicitly pass {@code null}; use sites
     * null-guard consistently (same pattern as {@code admissionGate}/{@code admissionMetrics}).
     */
    private final EligibleCellIndex eligibleCellIndex;

    /**
     * Phase 19 SCALE-07 (REVIEWS H3 / MEDIUM-6): LiveEntityRegistry for lifecycle
     * hooks at bot register and cleanup paths. Null when passed from back-compat ctors
     * (same null-guard pattern as eligibleCellIndex / admissionGate).
     */
    private final LiveEntityRegistry liveEntityRegistry;

    /**
     * Phase 19.1 D-09 — env-side state cleanup on disconnect.
     * Field-injected to avoid widening constructor signatures (back-compat ctors delegate
     * to the primary ctor and do not accept these collaborators). Null in back-compat tests
     * that do not exercise the cleanup path — all call sites null-guard.
     */
    @Autowired(required = false)
    private BuffRegistry buffRegistry;

    /** Phase 19.1 D-09 — infection + mutagen state cleanup on disconnect. */
    @Autowired(required = false)
    private DeathCleanupHooks deathCleanupHooks;

    /** Phase 19.1 D-09 — FLEEING state cleanup on disconnect. */
    @Autowired(required = false)
    private EnvironmentEngine environmentEngine;

    @Autowired
    public WorldWebSocketHandler(SessionRegistry sessionRegistry,
                                  WorldGrid worldGrid,
                                  TickEngine tickEngine,
                                  BotRegistry botRegistry,
                                  ActionResolver actionResolver,
                                  MetabolicProfile metabolicProfile,
                                  SpawnConfig spawnConfig,
                                  RespawnConfig respawnConfig,
                                  AdmissionGate admissionGate,
                                  OutboundSender outboundSender,
                                  ResumeTokenRegistry resumeTokenRegistry,
                                  AdmissionConfig admissionConfig,
                                  AdmissionMetrics admissionMetrics,
                                  EligibleCellIndex eligibleCellIndex,
                                  LiveEntityRegistry liveEntityRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.tickEngine = tickEngine;
        this.botRegistry = botRegistry;
        this.actionResolver = actionResolver;
        this.metabolicProfile = metabolicProfile;
        this.spawnConfig = spawnConfig;
        this.admissionGate = admissionGate;
        this.outboundSender = outboundSender;
        this.resumeTokenRegistry = resumeTokenRegistry;
        this.admissionConfig = admissionConfig;
        this.admissionMetrics = admissionMetrics;
        // REVIEWS MED-7: requireNonNull only for the production path (eligibleCellIndex != null
        // is guaranteed by Spring when this ctor is @Autowired). Back-compat ctors explicitly
        // pass null and do not exercise the placement path — this is documented and intentional.
        this.eligibleCellIndex = eligibleCellIndex;
        this.liveEntityRegistry = liveEntityRegistry;
        this.spawnRng = buildRng();
        // respawnConfig is kept only to satisfy Plan 10 migration; cap logic is in AdmissionGate.
        // maxRespawnsPerSession removed — AdmissionGate.evaluate handles the respawn-cap guard.
    }

    /**
     * Back-compat 7-arg convenience ctor for pre-Phase-17 direct-instantiation tests.
     * These tests do NOT exercise admission or backpressure paths.
     *
     * <p>REVIEWS MED-7: passes {@code null} for eligibleCellIndex explicitly — callers
     * of this ctor do not invoke the placement path, so NPE risk is controlled. The primary
     * @Autowired ctor enforces non-null for production wiring.
     */
    public WorldWebSocketHandler(SessionRegistry sessionRegistry,
                                  WorldGrid worldGrid,
                                  TickEngine tickEngine,
                                  BotRegistry botRegistry,
                                  ActionResolver actionResolver,
                                  MetabolicProfile metabolicProfile,
                                  SpawnConfig spawnConfig) {
        this(sessionRegistry, worldGrid, tickEngine, botRegistry, actionResolver,
                metabolicProfile, spawnConfig, RespawnConfig.defaults(),
                /* admissionGate */ null, /* outboundSender */ null,
                /* resumeTokenRegistry */ null, AdmissionConfig.defaults(),
                /* admissionMetrics */ null, /* eligibleCellIndex */ null,
                /* liveEntityRegistry */ null);
    }

    /**
     * Back-compat 6-arg convenience ctor.
     *
     * <p>REVIEWS MED-7: delegates to 7-arg; null eligibleCellIndex explicit — not used
     * at the placement path.
     */
    public WorldWebSocketHandler(SessionRegistry sessionRegistry,
                                  WorldGrid worldGrid,
                                  TickEngine tickEngine,
                                  BotRegistry botRegistry,
                                  ActionResolver actionResolver,
                                  MetabolicProfile metabolicProfile) {
        this(sessionRegistry, worldGrid, tickEngine, botRegistry, actionResolver,
                metabolicProfile, SpawnConfig.defaults());
    }

    private Random buildRng() {
        return spawnConfig.seed() == null ? new Random() : new Random(spawnConfig.seed());
    }

    /**
     * Phase 16 Plan 01: re-initialises {@link #spawnRng}. Test-only.
     */
    public void resetSeed() {
        this.spawnRng = buildRng();
    }

    /**
     * Phase 19 SCALE-06 (REVIEWS CONSENSUS-H6): PUBLIC test seam for placement determinism.
     *
     * <p>Exercises the same O(1) index-based placement path as an inbound {@code r|} frame
     * but without session attachment. Used by {@code PlacementDeterminismTest} to assert the
     * D-06 bit-exact contract across two seeded runs.
     *
     * <p>Production callers MUST use {@link #handleRegister} (inbound WS frame path).
     *
     * @param entityId    entity id to assign to the spawned particle
     * @param type        particle type
     * @param initialEnergy initial energy
     * @return placed position, or empty if the eligible set was exhausted (GRID_FULL)
     */
    public Optional<Position> attemptPlacementForTest(String entityId,
                                                       Entity.ParticleType type,
                                                       int initialEnergy) {
        if (eligibleCellIndex == null) return Optional.empty();
        Particle particle = Particle.spawn(entityId, type, initialEnergy);
        for (int attempt = 0; attempt < LOST_RACE_MAX_RETRIES; attempt++) {
            Position pos = eligibleCellIndex.sample(spawnRng);
            if (pos == null) return Optional.empty();
            if (worldGrid.trySetEntity(pos.x(), pos.y(), particle)) {
                eligibleCellIndex.notifyChanged(pos.x(), pos.y());
                return Optional.of(pos);
            }
            if (admissionMetrics != null) admissionMetrics.incLostRace();
            eligibleCellIndex.notifyChanged(pos.x(), pos.y());
        }
        return Optional.empty();
    }

    // ── @PostConstruct wiring ────────────────────────────────────────────────

    @PostConstruct
    void wireCrossBeanCallbacks() {
        if (outboundSender == null) return;   // back-compat ctors skip wiring

        // Plan 06 callback: queue overflow → markStalled (fire-once per attach via Plan 06 guard).
        outboundSender.setOverflowCallback((sessionId, depth) -> {
            long currentTick = tickEngine.currentTick();
            WebSocketSession s = sessionRegistry.getSession(sessionId);
            if (s == null) return;
            log.info("BACKPRESSURE stalled tick={} session={} queue-depth={} limit={} {}",
                    currentTick, sessionId, depth,
                    admissionConfig.backpressure().outboundQueueSize(),
                    AttributionTagger.formatLogFields(s));
            markStalled(s, currentTick);
        });

        // Plan 05 callback: grace expiry → reap by entityId.
        resumeTokenRegistry.setCleanupCallback(this::cleanupByEntityId);

        if (admissionConfig.maintenance()) {
            log.info("ADMISSION maintenance state=on");
        }
    }

    /**
     * TD-22.1-B: close-aware mass-detach at context teardown. WWSH depends on
     * {@link OutboundSender}, so Spring destroys WWSH first — this hook runs before
     * {@link OutboundSender#shutdown()}, draining every active session via the D-07
     * close-aware {@link OutboundSender#detachSession(WebSocketSession, CloseStatus)}
     * overload (transport-close unblocks any in-flight Jetty write so the drain VT exits).
     * That leaves OutboundSender's own {@code @PreDestroy} an idempotent no-op, eliminating
     * the 12 forkEvery=0 "Sender VT … did not exit within 100ms" teardown WARNs.
     *
     * <p>Note the close fires {@code afterConnectionClosed} → {@code cleanupBot} synchronously,
     * which touches WWSH's own injected collaborators (worldGrid, botRegistry, admissionMetrics …).
     * Safe for the same reason: Spring destroys WWSH before any bean it depends on, so those
     * collaborators are all still live during this hook. A future {@code @DependsOn} that inverts
     * that order would break this re-entrant cleanup.
     */
    @PreDestroy
    void shutdownDetachAll() {
        if (outboundSender == null) return;   // back-compat ctors skip detach
        for (WebSocketSession session : sessionRegistry.getActiveSessions()) {
            try {
                outboundSender.detachSession(session, CloseStatus.GOING_AWAY);
            } catch (RuntimeException e) {
                log.debug("Teardown detach failed for session={}: {}",
                        session.getId(), e.getMessage());
            }
        }
    }

    // ── Connection lifecycle ─────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionRegistry.register(session);
        if (outboundSender != null) {
            // Phase 20 D-02 — WS:entity 1:1 is a deliberate architectural choice, not an
            // optimisation gap. See RUNTIME.md §1 (and HARNESS.md §1, CLAUDE.md
            // §Connection model). Tuning per-connection cost is the equivalent
            // transport-level scale strategy (SCALE-08); collapsing entities onto a shared
            // session would require explicit ADR per D-21 of Phase 18.
            outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize());
        }

        // Phase 18 D-06: read handshake identity. Spring's HttpHeaders is case-insensitive.
        HttpHeaders headers = session.getHandshakeHeaders();
        String rawSource = headers.getFirst("X-Paralife-Source");
        String rawHarnessId = headers.getFirst("X-Paralife-Harness");

        // L1: length cap before further work — accepted client values are <= 8 chars.
        // H3: bounded CLIENT-ALLOWED subset (operator/harness/unknown). Server-only
        // taxonomy values (overflow, offspring) are NOT accepted from client headers.
        String source;
        if (rawSource == null || rawSource.isBlank() || rawSource.length() > 16) {
            source = "unknown";
        } else {
            source = rawSource.trim();
            if (!BotIdentity.CLIENT_ALLOWED_SOURCES.contains(source)) {
                source = "unknown";
            }
        }

        // M3 fold: if source=harness but harness id is missing/invalid, fold source to unknown.
        // Preserves the bidirectional invariant (source=harness IFF harness present), matching
        // BotIdentity's compact-ctor enforcement.
        if ("harness".equals(source)) {
            Optional<String> sanitized = AttributionSanitizer.sanitizeHarnessId(rawHarnessId);
            if (sanitized.isPresent()) {
                session.getAttributes().put(AttributionTagger.ATTR_HARNESS, sanitized.get());
            } else {
                source = "unknown";
            }
        }
        session.getAttributes().put(AttributionTagger.ATTR_SOURCE, source);

        long currentTick = tickEngine.currentTick();
        Object harnessAttr = session.getAttributes().get(AttributionTagger.ATTR_HARNESS);
        log.info("HARNESS connected tick={} session={} harness={} source={} active={}",
                currentTick, session.getId(),
                harnessAttr != null ? harnessAttr : "-", source,
                sessionRegistry.getSessionCount());
        log.info("Client connected: {} (total: {})", session.getId(), sessionRegistry.getSessionCount());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        boolean wasStalled = isStalled(session.getAttributes());

        // Capture attribution fields before any attribute removal.
        String closeReason = wasStalled ? "stalled-held" : "graceful";
        Object harnessAttr = session.getAttributes().get(AttributionTagger.ATTR_HARNESS);
        String harnessField = harnessAttr != null ? harnessAttr.toString() : "-";
        Object sourceAttr = session.getAttributes().get(AttributionTagger.ATTR_SOURCE);
        String sourceField = sourceAttr instanceof String s ? s : "unknown";

        // ALWAYS detach sender VT and unregister the WebSocket session.
        // Phase 22 (TD-19.5-A): pass the session so the transport is closed first —
        // unblocks any in-flight sendMessage so the drain VT exits cleanly without a
        // 100ms join timeout warning under shutdown-hook fanout.
        if (outboundSender != null) {
            outboundSender.detachSession(session);
        }
        sessionRegistry.unregister(sessionId);

        // Phase 18 D-14: HARNESS disconnected marker emitted on every exit path.
        log.info("HARNESS disconnected tick={} session={} harness={} source={} reason={}",
                tickEngine.currentTick(), sessionId, harnessField, sourceField, closeReason);

        if (wasStalled) {
            // Phase 17 D-12: do NOT reap entity — it is held in grace by ResumeTokenRegistry.
            // sweep @Order(1) is the sole reaper. The BotRegistry binding stays in place until
            // (a) client reconnects with the resume token (rebind swaps sessionId), or
            // (b) grace expires and cleanupByEntityId fires.
            log.info("BACKPRESSURE held-on-close tick={} session={} status={} entity={} {}",
                    tickEngine.currentTick(), sessionId, status,
                    session.getAttributes().get(ATTR_ENTITY_ID),
                    AttributionTagger.formatLogFields(session));
            return;
        }

        // Normal disconnect: clear ACTIVE token and run standard cleanup.
        // Pass the session reference directly so cleanup is independent of registry presence
        // (consensus CRITICAL fix — cleanup-after-unregister previously skipped slot release).
        Object entityIdObj = session.getAttributes().get(ATTR_ENTITY_ID);
        if (entityIdObj instanceof String eid && resumeTokenRegistry != null) {
            resumeTokenRegistry.clearActive(eid);
        }
        cleanupBot(session);
        log.info("Client disconnected: {} (total: {}, status: {})",
                sessionId, sessionRegistry.getSessionCount(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error for session {}: {}", session.getId(), exception.getMessage());
        boolean wasStalled = isStalled(session.getAttributes());
        // Phase 22 (TD-19.5-A): close-then-interrupt to avoid spurious VT-did-not-exit warnings.
        if (outboundSender != null) {
            outboundSender.detachSession(session);
        }
        sessionRegistry.unregister(session.getId());

        if (wasStalled) {
            // TD-20-01c-E: a STALLED session's entity is held on the grid for the grace-expiry
            // sweep (ResumeTokenRegistry @Order(1)). Mirror afterConnectionClosed:388 — do NOT
            // cleanupBot here. markStalled already cleared ATTR_ENTITY_ID, so cleanupBot would
            // (a) clear the held grid cell early via the botRegistry.getBySession block and
            // (b) skip liveEntityRegistry.unregister (guarded by entityId != null), orphaning
            // the registry entry. The grace-expiry sweep's cleanupByEntityId is the sole reaper.
            log.info("BACKPRESSURE transport-error-held tick={} session={}",
                    tickEngine.currentTick(), session.getId());
            return;
        }
        cleanupBot(session);
    }

    // ── Inbound message dispatch ─────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // STALLED guard: any inbound frame from a STALLED session gets an out-of-band 408.
        if (isStalled(session.getAttributes())) {
            sendOutOfBand(session, new Frame.ErrorFrame(408, Optional.of(RejectionToken.RECONNECT_REQUIRED)));
            if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.RECONNECT_REQUIRED, session);
            try { session.close(CloseStatus.SERVICE_RESTARTED); } catch (IOException ignored) {}
            return;
        }

        Frame frame;
        try {
            frame = PerceptionCodec.decode(message.getPayload());
        } catch (CodecException e) {
            log.warn("Malformed frame from {}: {}", session.getId(), e.getMessage());
            sendFrame(session, new Frame.ErrorFrame(400, Optional.of(RejectionToken.MALFORMED)));
            if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.MALFORMED, session);
            return;
        }

        switch (frame) {
            case Frame.RegisterFrame r -> handleRegister(session, r);
            case Frame.ActionFrame a -> handleAction(session, a);
            case Frame.SyncFrame ignored -> {
                sendFrame(session, new Frame.ErrorFrame(400, Optional.of(RejectionToken.MALFORMED)));
                if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.MALFORMED, session);
            }
            case Frame.TickFrame ignored -> {
                sendFrame(session, new Frame.ErrorFrame(400, Optional.of(RejectionToken.MALFORMED)));
                if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.MALFORMED, session);
            }
            case Frame.ErrorFrame ignored -> { /* ignore client-sent errors */ }
        }
    }

    // ── Registration ─────────────────────────────────────────────────────────

    private void handleRegister(WebSocketSession session, Frame.RegisterFrame register) {
        var attrs = session.getAttributes();
        long currentTick = tickEngine.currentTick();
        boolean alreadyAlive = isAlive(attrs);
        boolean isRespawn = attrs.containsKey(ATTR_ENTITY_TYPE);
        int respawnCount = respawnCountOf(attrs);

        AdmissionGate.AdmissionRequest req = new AdmissionGate.AdmissionRequest(
                session.getId(), currentTick, alreadyAlive, isRespawn, respawnCount,
                register.resumeToken());

        AdmissionResult result = (admissionGate != null)
                ? admissionGate.evaluate(req, session)
                : AdmissionResult.Allow.INSTANCE;

        if (result instanceof AdmissionResult.Reject reject) {
            sendFrame(session, new Frame.ErrorFrame(reject.code(), Optional.of(reject.token())));
            return;
        }

        if (result instanceof AdmissionResult.Rebind rebind) {
            // Resume-token re-bind: preserve entityId, swap session in BotRegistry, restore respawn count.
            //
            // P18-Chunk-A H1 fix: gauge accounting under attribution change.
            // The original Allow incremented the gauge keyed by the OLD session's tags
            // (captured in the snapshot). When the rebound session presents different
            // attribution headers (operator JVM restart with auto-uuid harness, missing
            // headers, etc.) we must:
            //   1. drop the OLD stalled-bucket gauge   (decStalledBucketByTags(snapshot))
            //   2. drop the OLD active-bucket gauge    (decActiveBucketByTags(snapshot))
            //   3. re-increment the NEW active-bucket  (incActiveBucket — uses live session tags
            //      and updates the entityId→Tags snapshot to point at the new bucket)
            //
            // For the same-attribution case (snapshot == new tags) this is a net no-op.
            // Both buckets stay at their pre-rebind values and the snapshot is rewritten to itself.
            // Inserting ATTR_ENTITY_ID into attrs BEFORE incActiveBucket is required — the
            // incActiveBucket call captures the snapshot keyed by the entity id read off attrs.
            attrs.remove(ATTR_STALL_TICK);
            attrs.put(ATTR_ENTITY_ID, rebind.entityId());
            attrs.put(ATTR_ENTITY_TYPE, register.entityType());
            attrs.put(ATTR_RESUME_TOKEN, rebind.freshResumeToken());

            if (admissionMetrics != null) {
                io.micrometer.core.instrument.Tags oldTags =
                        admissionMetrics.lookupBucketTags(rebind.entityId());
                if (oldTags != null) {
                    admissionMetrics.decStalledBucketByTags(oldTags);
                    admissionMetrics.decActiveBucketByTags(oldTags);
                }
                admissionMetrics.incActiveBucket(session);
            }
            // Restore respawn count from stall-time snapshot (claude MEDIUM respawn-cap-bypass fix).
            Integer snapshot = respawnCountAtStall.remove(rebind.entityId());
            if (snapshot != null) {
                attrs.put(ATTR_RESPAWN_COUNT, snapshot);
            }
            // Phase 19.5 M-F: rebindSession returns false when the entity is no
            // longer in BotRegistry (already reaped between tryRebind and here —
            // e.g. grace expiry sweep ran on the same tick). Treat as a stale
            // resume token: tell the client to re-register fresh + close.
            if (!botRegistry.rebindSession(session.getId(), rebind.entityId())) {
                sendFrame(session, new Frame.ErrorFrame(400, Optional.of("stale-resume-token")));
                try { session.close(); } catch (Exception ignored) {}
                log.warn("BACKPRESSURE rebind-stale tick={} session={} entity={} {}",
                        currentTick, session.getId(), rebind.entityId(),
                        AttributionTagger.formatLogFields(session));
                return;
            }
            sendFrame(session, new Frame.SyncFrame(rebind.entityId(),
                    Optional.of(rebind.freshResumeToken()), List.of()));
            log.info("BACKPRESSURE resumed tick={} session={} entity={} respawnCountRestored={} {}",
                    currentTick, session.getId(), rebind.entityId(), snapshot,
                    AttributionTagger.formatLogFields(session));
            return;
        }

        // result == Allow → place entity (fresh registration or respawn).
        ParticleType particleType;
        if (isRespawn) {
            particleType = switch ((Character) attrs.get(ATTR_ENTITY_TYPE)) {
                case 'C' -> ParticleType.CATALYST;
                case 'M' -> ParticleType.MEMBRANE;
                case 'S' -> ParticleType.SPORE;
                default -> ParticleType.CATALYST;
            };
        } else {
            particleType = switch (register.entityType()) {
                case 'C' -> ParticleType.CATALYST;
                case 'M' -> ParticleType.MEMBRANE;
                case 'S' -> ParticleType.SPORE;
                default -> ParticleType.CATALYST;
            };
        }

        String entityId = "entity-" + session.getId()
                + (isRespawn ? ("-r" + (respawnCount + 1)) : "");

        int maxEnergy = metabolicProfile.forType(particleType).maxEnergy();
        Particle particle = Particle.spawn(entityId, particleType, maxEnergy);

        // Phase 19 SCALE-06 (D-01): O(1) placement from eligible-cell index.
        // REVIEWS L4 / LOW-12: bounded 3-retry on lost-race (concurrent registration
        // both sampled the same cell; only one can win trySetEntity).
        //
        // Phase 19.5 H3: register in LiveEntityRegistry BEFORE the WorldGrid mutation
        // (with rollback on trySetEntity failure). Tick read at @Order(10)
        // (SimulationEngine.entitySnapshot via liveEntityRegistry.snapshot()) can fire
        // mid-sequence; consumers re-derive the entity from
        // worldGrid.getCell(entry.position()).occupant(), so a "registry has it,
        // grid doesn't" transient resolves to a benign skip on the consumer side.
        // The opposite transient (grid has it, registry doesn't) caused entity-list
        // iteration to skip the entity for one tick — pre-Phase-19 grid-walk would
        // have caught it.
        Position pos = null;
        boolean placed = false;
        if (eligibleCellIndex != null) {
            for (int attempt = 0; attempt < LOST_RACE_MAX_RETRIES; attempt++) {
                pos = eligibleCellIndex.sample(spawnRng);
                if (pos == null) break; // eligible set empty → GRID_FULL
                // H3: register-first, then grid-mutate.
                if (liveEntityRegistry != null) {
                    liveEntityRegistry.register(entityId, pos);
                }
                if (worldGrid.trySetEntity(pos.x(), pos.y(), particle)) {
                    placed = true;
                    break;
                }
                // H3: lost race — roll back the LiveEntityRegistry insert so the next
                // attempt registers cleanly under a fresh sample.
                if (liveEntityRegistry != null) {
                    liveEntityRegistry.unregister(entityId);
                }
                // Lost race: another registration won trySetEntity. Increment counter,
                // re-evaluate eligibility for the contested cell, then retry.
                if (admissionMetrics != null) admissionMetrics.incLostRace();
                eligibleCellIndex.notifyChanged(pos.x(), pos.y());
            }
        } else {
            // Back-compat path: no index available (back-compat ctors, tests). Use legacy scan.
            // No LiveEntityRegistry to coordinate with on this path (back-compat ctors pass null).
            for (int attempt = 0; attempt < 50; attempt++) {
                int x = spawnRng.nextInt(worldGrid.getWidth());
                int y = spawnRng.nextInt(worldGrid.getHeight());
                if (worldGrid.trySetEntity(x, y, particle)) {
                    pos = new Position(x, y);
                    placed = true;
                    break;
                }
            }
        }

        if (!placed) {
            // Only fresh registrations consumed a slot at admission; respawn reuses existing slot.
            if (!isRespawn && admissionGate != null) admissionGate.releaseSlot();
            if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.GRID_FULL, session);
            sendFrame(session, new Frame.ErrorFrame(503, Optional.of(RejectionToken.GRID_FULL)));
            return;
        }

        // Notify index of successful placement (5×5 dirty bbox).
        if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(pos.x(), pos.y());

        botRegistry.register(session.getId(), entityId, pos);
        // Phase 19.5 H3: LiveEntityRegistry.register has already been called above
        // (register-first ordering); no additional call needed here.

        // Issue ACTIVE resume token (D-13: first sync carries S|<entityId>|<resumeToken>).
        String resumeToken = (resumeTokenRegistry != null)
                ? resumeTokenRegistry.issueActive(entityId, session.getId())
                : null;

        // Update FSM attributes.
        attrs.put(ATTR_ENTITY_ID, entityId);
        if (!isRespawn) {
            attrs.put(ATTR_ENTITY_TYPE, register.entityType());
        } else {
            attrs.put(ATTR_RESPAWN_COUNT, respawnCount + 1);
        }
        // REQUIRED: markStalled needs this local cache to convertToStalled by token (D-13).
        if (resumeToken != null) {
            attrs.put(ATTR_RESUME_TOKEN, resumeToken);
        }

        // D-13 wire shape: S|<entityId>|<resumeToken>
        sendFrame(session, new Frame.SyncFrame(entityId, Optional.of(resumeToken), List.of()));

        if (admissionMetrics != null) {
            admissionMetrics.incActiveBucket(session);
        }
        log.info("Entity registered: {} at ({},{}) type={} respawnCount={}",
                entityId, pos.x(), pos.y(), particleType, attrs.get(ATTR_RESPAWN_COUNT));
    }

    private void handleAction(WebSocketSession session, Frame.ActionFrame action) {
        Object entityId = session.getAttributes().get(ATTR_ENTITY_ID);
        if (entityId == null) {
            sendFrame(session, new Frame.ErrorFrame(404, Optional.of(RejectionToken.NO_ACTIVE_ENTITY)));
            if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.NO_ACTIVE_ENTITY, session);
            return;
        }
        actionResolver.queueAction(session.getId(), action);
        log.debug("Action queued from {}: verb={}", session.getId(), action.verb());
    }

    // ── FSM predicates ───────────────────────────────────────────────────────

    /** Map overload — used internally by handleTextMessage / afterConnectionClosed. */
    boolean isAlive(Map<String, Object> attrs) {
        return attrs.containsKey(ATTR_ENTITY_ID);
    }

    /** Map overload — used internally by handleTextMessage / afterConnectionClosed. */
    boolean isStalled(Map<String, Object> attrs) {
        return attrs.containsKey(ATTR_STALL_TICK);
    }

    /**
     * Public overload — REQUIRED by Plan 08 (TickBroadcaster.onTick / drainAndBroadcastDeaths
     * skip path). Without this signature Plan 08 fails to compile.
     */
    public boolean isStalled(WebSocketSession session) {
        return session != null && isStalled(session.getAttributes());
    }

    private static int respawnCountOf(Map<String, Object> attrs) {
        Object v = attrs.get(ATTR_RESPAWN_COUNT);
        if (v instanceof Integer i) return i;
        return 0;
    }

    // ── STALLED FSM transition ───────────────────────────────────────────────

    /**
     * Idempotent STALLED transition. If already STALLED (ATTR_STALL_TICK present), returns
     * immediately (codex HIGH idempotency). Otherwise:
     * <ol>
     *   <li>Sets ATTR_STALL_TICK (idempotency gate).</li>
     *   <li>Snapshots respawn count for restore-on-rebind (claude MEDIUM).</li>
     *   <li>Converts the ACTIVE resume token to STALLED in the registry.</li>
     *   <li>Detaches OutboundSender VT via the close-aware overload
     *       ({@code detachSession(session, SERVICE_RESTARTED)}) — the wire-level close fires
     *       first, unblocking any in-flight Jetty write so the drain VT exits cleanly. The
     *       close itself is the reconnect signal (Phase 19.1 D-07).</li>
     *   <li>Best-effort E|408|reconnect-required OOB via {@link #sendOutOfBand}; short-circuited
     *       by the {@code isOpen()} guard once the transport has been closed.</li>
     * </ol>
     *
     * <p>No second {@code session.close(...)} is issued — the close-aware detach already
     * carried {@code SERVICE_RESTARTED} to the wire.
     */
    public void markStalled(WebSocketSession session, long stallTick) {
        if (session == null) return;
        var attrs = session.getAttributes();

        // Idempotent guard (codex HIGH): if already STALLED, do nothing.
        if (attrs.containsKey(ATTR_STALL_TICK)) return;

        // SLI denominator: count this real stall transition (post-idempotency guard).
        if (admissionMetrics != null) admissionMetrics.incStalledTotal();

        // === ROUND 2 CLAUDE HIGH AMENDMENT ===
        // Read entityId BEFORE attrs.remove. Pass explicitly to incStalledBucket so
        // bucketTagsByEntityId snapshot is captured with the real entityId — NOT null.
        Object entityIdObj = attrs.get(ATTR_ENTITY_ID);
        String entityId = entityIdObj != null ? entityIdObj.toString() : null;
        if (admissionMetrics != null && entityId != null) {
            admissionMetrics.incStalledBucket(session, entityId);
        }
        // === END AMENDMENT ===

        attrs.put(ATTR_STALL_TICK, stallTick);
        // NOW the existing remove proceeds.
        attrs.remove(ATTR_ENTITY_ID);
        Object tokenObj = attrs.get(ATTR_RESUME_TOKEN);
        String activeToken = tokenObj == null ? null : tokenObj.toString();

        // Snapshot respawn count so rebind can restore it (claude MEDIUM respawn-cap-bypass fix).
        if (entityId != null) {
            respawnCountAtStall.put(entityId, respawnCountOf(attrs));
        }

        // Convert ACTIVE → STALLED in registry.
        if (activeToken != null && resumeTokenRegistry != null) {
            resumeTokenRegistry.convertToStalled(activeToken, stallTick);
        } else if (entityId != null) {
            log.warn("markStalled: session={} entity={} has no cached resume token; entity will be unrecoverable",
                    session.getId(), entityId);
        }

        // Phase 19.1 D-07 — close-aware detach with SERVICE_RESTARTED status.
        // Transport closes first, causing the blocked Jetty write to throw
        // IOException and the drain VT to exit. The wire-level close frame
        // carries the semantically-correct status code in one round-trip.
        if (outboundSender != null) {
            outboundSender.detachSession(session, CloseStatus.SERVICE_RESTARTED);
        }

        // OOB 408 is best-effort — the session is already closed by detachSession.
        // sendOutOfBand carries an isOpen() guard at line 965; the close itself is the
        // reconnect signal — OOB is not load-bearing (D3-M1).
        sendOutOfBand(session, new Frame.ErrorFrame(408, Optional.of(RejectionToken.RECONNECT_REQUIRED)));
        // The redundant session.close(SERVICE_RESTARTED) previously here is REMOVED:
        // detachSession(session, SERVICE_RESTARTED) already closed the transport with
        // the same status. A second close is a no-op at best, masks IOException at worst.
    }

    // ── Cleanup helpers ──────────────────────────────────────────────────────

    /**
     * Phase 17 D-12: reap an entity by ID. Invoked by {@link ResumeTokenRegistry} sweep when
     * grace expires. Idempotent: if entityId is unknown, this is a no-op.
     *
     * <p>Critical: when called via the stalled→grace-expire path, the WebSocket session was
     * already unregistered at stalled-close time. We therefore use the bucket-tags snapshot
     * (captured at admission time) to decrement gauges and explicitly release the slot —
     * the registry-driven {@link #cleanupBot(WebSocketSession)} path cannot help here.
     */
    public void cleanupByEntityId(String entityId) {
        if (entityId == null) return;
        // Phase 17: terminal dropout — STALLED token expired before reconnect.
        // Operator SLI: rising counter indicates either widespread slow-consumer conditions
        // or grace-window mis-tuning.
        if (admissionMetrics != null) admissionMetrics.incTerminalDropout();

        // Phase 19.1 D-09 (A4.3 amendment) — env-state cleanup BEFORE the
        // sessionId-null early-return so entityId-only paths (admission abort,
        // post-mark-dead cleanup) don't leak env-state.
        if (buffRegistry != null) buffRegistry.unregisterEntity(entityId);
        if (deathCleanupHooks != null) deathCleanupHooks.clearInfectionOnDeath(entityId);
        if (environmentEngine != null) environmentEngine.fleeingRemove(entityId);
        // Multi-review R1 (codex/gemini/claude): the :877 ATTR_ENTITY_ID strip routes the delegated
        // cleanupBot onto its entityId==null path, which skips clearActive. Mirror it here alongside
        // the env-state cleanups above. Idempotent and a no-op for the stalled grace-expiry path
        // (the ACTIVE token was already converted to STALLED at markStalled); it only matters for a
        // direct non-stalled cleanupByEntityId, which would otherwise leak an ACTIVE resume-token
        // entry — persistent shared-JVM state under forkEvery=0.
        if (resumeTokenRegistry != null) resumeTokenRegistry.clearActive(entityId);

        String sessionId = botRegistry.getSessionByEntity(entityId).orElse(null);
        io.micrometer.core.instrument.Tags bucketTags = admissionMetrics != null
                ? admissionMetrics.lookupBucketTags(entityId) : null;
        respawnCountAtStall.remove(entityId);

        if (sessionId == null) {
            log.debug("cleanupByEntityId: entityId={} has no bound session (already reaped?)", entityId);
            if (admissionMetrics != null && bucketTags != null) {
                admissionMetrics.decActiveBucketByTags(bucketTags);
                admissionMetrics.decStalledBucketByTags(bucketTags);
            }
            if (admissionMetrics != null) admissionMetrics.releaseBucketTags(entityId);
            return;
        }

        // Drop the stalled gauge here (snapshot keyed by entityId — survives session unregister).
        if (admissionMetrics != null && bucketTags != null) {
            admissionMetrics.decStalledBucketByTags(bucketTags);
        }

        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session != null) {
            // Pass-3 R-P3-1: session was stalled (entityId cleared from attrs at markStalled),
            // so cleanupBot will hit the H1 entityId==null guard and skip active-bucket dec.
            // Own the dec + snapshot release here BEFORE cleanupBot — otherwise the gauge
            // and bucketTagsByEntityId both leak on the rare race where grace-expire fires
            // before afterConnectionClosed unregisters the session.
            if (admissionMetrics != null && bucketTags != null) {
                admissionMetrics.decActiveBucketByTags(bucketTags);
            }
            if (admissionMetrics != null) admissionMetrics.releaseBucketTags(entityId);
            // Pass-4 M2 (codex): cleanupBot unregisters LiveEntityRegistry only when its
            // session-attr entityId is non-null — but markStalled cleared ATTR_ENTITY_ID, so
            // the delegated cleanupBot below skips it (the entityId!=null guard at ~:929 is
            // false). Unregister here with the method-param entityId (mirrors the
            // session-unregistered branch below), else this rare still-registered grace-expiry
            // leaks a stale entry into LiveEntityRegistry while cleanupBot clears the grid cell.
            if (liveEntityRegistry != null) liveEntityRegistry.unregister(entityId);
            // Full cleanup (slot release + grid + BotRegistry). cleanupBot's entityId==null
            // guard is now correct: we have already dec'd+released+unregistered above.
            // Enforce the :851 stalled-session precondition rather than assume it: clear
            // ATTR_ENTITY_ID so cleanupBot takes its entityId==null path and does NOT dec the
            // active bucket a second time. The sole production caller (grace-expiry sweep) always
            // passes an already-stalled session (attr absent → this remove is a no-op); a
            // non-stalled caller (e.g. direct cleanupByEntityId in tests) would otherwise drive
            // the bucket gauge negative via cleanupBot's fallback dec at the snapshot-released
            // line below. Env-state (buff/infection/fleeing) is already cleared at :825-827, so
            // skipping cleanupBot's entityId!=null env branch loses nothing.
            session.getAttributes().remove(ATTR_ENTITY_ID);
            cleanupBot(session);
        } else {
            // Session unregistered (typical stalled-close path). Manually drop active gauge,
            // release slot, clear grid, unregister from BotRegistry.
            if (admissionMetrics != null && bucketTags != null) {
                admissionMetrics.decActiveBucketByTags(bucketTags);
            }
            if (admissionMetrics != null) admissionMetrics.releaseBucketTags(entityId);
            botRegistry.getBySession(sessionId).ifPresent(state -> {
                var pos = state.position();
                worldGrid.clearEntity(pos.x(), pos.y());
                // REVIEWS MED-6: notify eligible-cell index after structural grid clear.
                if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(pos.x(), pos.y());
            });
            // Phase 19 SCALE-07 (REVIEWS MEDIUM-6): unregister from LiveEntityRegistry on stalled-close path.
            if (liveEntityRegistry != null) liveEntityRegistry.unregister(entityId);
            botRegistry.unregisterBySession(sessionId);
            if (admissionGate != null) admissionGate.releaseSlot();
        }
    }

    /**
     * Remove bot's entity from the grid, decrement bucket gauges, and release the admission slot.
     *
     * <p>Takes a {@link WebSocketSession} reference (not just sessionId) so cleanup works
     * regardless of whether the session is still registered in {@link SessionRegistry} —
     * fixes the consensus CRITICAL where {@code afterConnectionClosed} unregistered before
     * cleaning up, leaking a slot per graceful disconnect.
     *
     * <p>H1 fix: active gauge dec uses the {@link AdmissionMetrics#lookupBucketTags} snapshot
     * keyed by entityId, NOT live session tags. This keeps rebind-across-attribution-change
     * accounting correct (the original bucket gets the dec, not a new one derived from the
     * rebound session's possibly-different harness id).
     *
     * <p>Idempotent: relies on the {@code ATTR_ENTITY_TYPE} marker for "wasRegistered" detection,
     * so a second invocation on the same session is a no-op.
     */
    /**
     * Phase 19.5 H-A — {@link EntityLifecycleListener} callback fired by
     * {@code SimulationEngine} immediately after a {@link BotRegistry} remap
     * (bond formation, composite formation, composite revert, dissolve).
     * Updates {@code ATTR_ENTITY_ID} so subsequent {@link #cleanupBot} reaches
     * the right {@link LiveEntityRegistry} entry, and rewrites any STALLED
     * resume-token entry keyed by {@code oldEntityId} so reconnect resolves to
     * the post-remap entity (Phase 19.5 H-C).
     *
     * <p>If the session is no longer present (already disconnected before the
     * remap fired), this is a no-op — the registry remap is still correct and
     * the next tick cleans up the orphan via registry-driven paths.
     */
    @Override
    public void onEntityRemapped(String sessionId, String oldEntityId, String newEntityId) {
        if (resumeTokenRegistry != null) {
            resumeTokenRegistry.remapEntity(oldEntityId, newEntityId);
        }
        // Phase 19.1 D-08 — keep AdmissionMetrics bucket-tags map aligned with remap.
        if (admissionMetrics != null) {
            admissionMetrics.remapBucketTags(oldEntityId, newEntityId);
        }
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null) {
            log.debug("onEntityRemapped: session {} no longer present — skipping attr update",
                    sessionId);
            return;
        }
        session.getAttributes().put(ATTR_ENTITY_ID, newEntityId);
        log.debug("onEntityRemapped: session={} {} -> {}", sessionId, oldEntityId, newEntityId);
    }

    public void cleanupBot(WebSocketSession s) {
        if (s == null) return;
        String sessionId = s.getId();
        Object eid = s.getAttributes().remove(ATTR_ENTITY_ID);
        s.getAttributes().remove(ATTR_STALL_TICK);
        s.getAttributes().remove(ATTR_RESUME_TOKEN);
        // ATTR_ENTITY_TYPE is the durable "session ever admitted" marker. Removing makes
        // slot release idempotent against repeated cleanupBot calls.
        boolean wasRegistered = s.getAttributes().remove(ATTR_ENTITY_TYPE) != null;
        String entityId = eid instanceof String e ? e : null;

        if (entityId != null) {
            respawnCountAtStall.remove(entityId);
            if (resumeTokenRegistry != null) resumeTokenRegistry.clearActive(entityId);
            // Phase 19.1 D-09 — env-side state ownership: disconnect must release these.
            // DeathFinalizer normally clears them, but disconnect-without-death never
            // reaches it. Without this, BuffRegistry/infection/FLEEING leak per disconnect.
            if (buffRegistry != null) buffRegistry.unregisterEntity(entityId);
            if (deathCleanupHooks != null) deathCleanupHooks.clearInfectionOnDeath(entityId);
            if (environmentEngine != null) environmentEngine.fleeingRemove(entityId);
        }
        botRegistry.getBySession(sessionId).ifPresent(state -> {
            var pos = state.position();
            worldGrid.clearEntity(pos.x(), pos.y());
            // REVIEWS MED-6: notify eligible-cell index after structural grid clear.
            if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(pos.x(), pos.y());
        });
        // Phase 19 SCALE-07 (REVIEWS MEDIUM-6): unregister from LiveEntityRegistry on cleanupBot.
        if (liveEntityRegistry != null && entityId != null) liveEntityRegistry.unregister(entityId);
        botRegistry.unregisterBySession(sessionId);

        if (wasRegistered && admissionGate != null) {
            admissionGate.releaseSlot();
        }
        if (wasRegistered && admissionMetrics != null) {
            if (entityId != null) {
                // Prefer snapshot Tags (keyed by entityId) over session-derived tags.
                // Snapshot is captured at incActiveBucket time and survives session-attr churn.
                io.micrometer.core.instrument.Tags bucketTags =
                        admissionMetrics.lookupBucketTags(entityId);
                if (bucketTags != null) {
                    admissionMetrics.decActiveBucketByTags(bucketTags);
                } else {
                    // Fallback: session tags. Hits only when no snapshot was ever captured
                    // (e.g. legacy tests calling cleanupBot without going through Allow path).
                    admissionMetrics.decActiveBucket(s);
                }
                // C2 fix: drop entityId snapshot from bucketTagsByEntityId — prevents unbounded growth.
                admissionMetrics.releaseBucketTags(entityId);
            }
            // entityId == null: either markDead already dec'd (path C) or markStalled
            // cleared entityId and cleanupByEntityId at grace-expire owns the dec via the
            // bucketTagsByEntityId snapshot (path B). Either way cleanupBot must NOT dec
            // here — would double-dec the bucket otherwise (pass-2 R1 + TD-20-01c-B).
        }
    }

    // ── Outbound ─────────────────────────────────────────────────────────────

    /**
     * Route a frame through {@link OutboundSender}. Non-blocking — if the queue is full
     * or detached, the frame is dropped (overflow callback handles the stall transition).
     */
    void sendFrame(WebSocketSession session, Frame frame) {
        if (session == null || !session.isOpen()) return;
        if (outboundSender != null) {
            boolean accepted = outboundSender.offer(session.getId(), frame);
            if (!accepted) {
                log.debug("sendFrame dropped for session={} (queue full or detached)", session.getId());
            }
        } else {
            // Fallback for back-compat ctors (no OutboundSender wired).
            try {
                String encoded = PerceptionCodec.encode(frame);
                synchronized (session) {
                    session.sendMessage(new TextMessage(encoded));
                }
            } catch (Exception e) {
                log.warn("Failed to send frame to {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    /**
     * Direct synchronized send — used ONLY when bypassing the queue (markStalled / stalled-inbound 408).
     * Best-effort: the {@code isOpen()} guard short-circuits once the transport has been closed
     * (e.g., by the close-aware {@code detachSession(session, status)} overload).
     */
    private void sendOutOfBand(WebSocketSession session, Frame frame) {
        if (session == null || !session.isOpen()) return;
        try {
            String encoded = PerceptionCodec.encode(frame);
            synchronized (session) {
                session.sendMessage(new TextMessage(encoded));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Out-of-band send failed for {}: {}", session.getId(), e.getMessage());
        }
    }

    /** Convenience wrapper for send-error-frame callers. */
    public void sendErrorFrame(WebSocketSession session, int code, String msg) {
        sendFrame(session, new Frame.ErrorFrame(code, Optional.ofNullable(msg)));
    }

    /**
     * Transition the session from Alive to Dead (respawn pending). Called by
     * the downstream broadcaster (plan 15-08) when it detects the session's
     * entity has been removed from the grid.
     */
    public void markDead(WebSocketSession session) {
        if (session == null) return;
        Object eid = session.getAttributes().remove(ATTR_ENTITY_ID);
        session.getAttributes().remove(ATTR_RESUME_TOKEN);
        String entityId = eid instanceof String e ? e : null;
        if (entityId != null) {
            if (admissionMetrics != null) {
                io.micrometer.core.instrument.Tags bucketTags =
                        admissionMetrics.lookupBucketTags(entityId);
                if (bucketTags != null) {
                    admissionMetrics.decActiveBucketByTags(bucketTags);
                }
                admissionMetrics.releaseBucketTags(entityId);
            }
            if (resumeTokenRegistry != null) {
                resumeTokenRegistry.clearActive(entityId);
            }
        }
    }
}
