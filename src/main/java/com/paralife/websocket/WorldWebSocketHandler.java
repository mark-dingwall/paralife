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
import com.paralife.engine.MetabolicProfile;
import com.paralife.engine.SpawnConfig;
import com.paralife.engine.TickEngine;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
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
 * <p><b>Out-of-band 408 delivery:</b> {@link #markStalled} detaches the OutboundSender VT
 * first (joins for up to 100ms), then sends the error frame directly via
 * {@code synchronized(session)} — guaranteed delivery even when the queue is saturated.
 */
@Component
public class WorldWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorldWebSocketHandler.class);

    // ── FSM session attribute keys ───────────────────────────────────────────
    private static final String ATTR_ENTITY_ID     = "entityId";
    private static final String ATTR_ENTITY_TYPE   = "entityType";
    private static final String ATTR_RESPAWN_COUNT = "respawnCount";
    /** Phase 17: set to the tick number when session transitions to STALLED. */
    private static final String ATTR_STALL_TICK    = "stallTick";
    /** Phase 17: local cache of the ACTIVE resume token so markStalled can convertToStalled. */
    private static final String ATTR_RESUME_TOKEN  = "resumeToken";

    /** Max random-placement attempts before declaring the grid effectively full. */
    private static final int MAX_PLACEMENT_ATTEMPTS = 50;

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
                                  AdmissionMetrics admissionMetrics) {
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
        this.spawnRng = buildRng();
        // respawnConfig is kept only to satisfy Plan 10 migration; cap logic is in AdmissionGate.
        // maxRespawnsPerSession removed — AdmissionGate.evaluate handles the respawn-cap guard.
    }

    /**
     * Back-compat 7-arg convenience ctor for pre-Phase-17 direct-instantiation tests.
     * These tests do NOT exercise admission or backpressure paths.
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
                /* admissionMetrics */ null);
    }

    /**
     * Back-compat 6-arg convenience ctor.
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

    // ── Connection lifecycle ─────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionRegistry.register(session);
        if (outboundSender != null) {
            outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize());
        }

        // Phase 18 D-06: read handshake identity. Spring's HttpHeaders is case-insensitive.
        HttpHeaders headers = session.getHandshakeHeaders();
        String rawSource = headers.getFirst("X-Paralife-Source");
        String rawHarnessId = headers.getFirst("X-Paralife-Harness");

        // Source: bounded-taxonomy filter (T-18-01). Values outside taxonomy fold to "unknown".
        String source = (rawSource == null || rawSource.isBlank())
                ? "unknown"
                : rawSource.trim();
        if (!BotIdentity.SOURCE_TAXONOMY.contains(source)) {
            source = "unknown";
        }
        session.getAttributes().put(AttributionTagger.ATTR_SOURCE, source);

        // Harness: server-side sanitization via the shared helper (Round 2 Codex HIGH).
        // Only stash if source=harness AND sanitizer accepts the value. Sanitizer rejects
        // ASCII control chars including CR/LF; truncates to 32 chars.
        if ("harness".equals(source)) {
            AttributionSanitizer.sanitizeHarnessId(rawHarnessId).ifPresent(sanitized ->
                    session.getAttributes().put(AttributionTagger.ATTR_HARNESS, sanitized));
        }

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
        if (outboundSender != null) {
            outboundSender.detachSession(sessionId);
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
        Object entityIdObj = session.getAttributes().get(ATTR_ENTITY_ID);
        if (entityIdObj instanceof String eid && resumeTokenRegistry != null) {
            resumeTokenRegistry.clearActive(eid);
        }
        cleanupBot(sessionId);
        log.info("Client disconnected: {} (total: {}, status: {})",
                sessionId, sessionRegistry.getSessionCount(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error for session {}: {}", session.getId(), exception.getMessage());
        if (outboundSender != null) {
            outboundSender.detachSession(session.getId());
        }
        cleanupBot(session.getId());
        sessionRegistry.unregister(session.getId());
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
                ? admissionGate.evaluate(req)
                : AdmissionResult.Allow.INSTANCE;

        if (result instanceof AdmissionResult.Reject reject) {
            sendFrame(session, new Frame.ErrorFrame(reject.code(), Optional.of(reject.token())));
            return;
        }

        if (result instanceof AdmissionResult.Rebind rebind) {
            // Resume-token re-bind: preserve entityId, swap session in BotRegistry, restore respawn count.
            // Round 2 Claude MEDIUM: rebind decrements OLD stalled bucket via the snapshot.
            // Active bucket is NOT modified — it stays incremented from the original Allow path.
            // Do NOT call incActiveBucket here — that would double-count.
            if (admissionMetrics != null) {
                io.micrometer.core.instrument.Tags oldTags =
                        admissionMetrics.lookupBucketTags(rebind.entityId());
                if (oldTags != null) {
                    admissionMetrics.decStalledBucketByTags(oldTags);
                }
            }
            attrs.remove(ATTR_STALL_TICK);
            attrs.put(ATTR_ENTITY_ID, rebind.entityId());
            attrs.put(ATTR_ENTITY_TYPE, register.entityType());
            attrs.put(ATTR_RESUME_TOKEN, rebind.freshResumeToken());
            // Restore respawn count from stall-time snapshot (claude MEDIUM respawn-cap-bypass fix).
            Integer snapshot = respawnCountAtStall.remove(rebind.entityId());
            if (snapshot != null) {
                attrs.put(ATTR_RESPAWN_COUNT, snapshot);
            }
            botRegistry.rebindSession(session.getId(), rebind.entityId());
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

        Random rng = spawnRng;
        int x = -1, y = -1;
        boolean placed = false;
        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            x = rng.nextInt(worldGrid.getWidth());
            y = rng.nextInt(worldGrid.getHeight());
            if (worldGrid.trySetEntity(x, y, particle)) {
                placed = true;
                break;
            }
        }

        if (!placed) {
            // Only fresh registrations consumed a slot at admission; respawn reuses existing slot.
            if (!isRespawn && admissionGate != null) admissionGate.releaseSlot();
            if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.GRID_FULL, session);
            sendFrame(session, new Frame.ErrorFrame(503, Optional.of(RejectionToken.GRID_FULL)));
            return;
        }

        botRegistry.register(session.getId(), entityId, new Position(x, y));

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
                entityId, x, y, particleType, attrs.get(ATTR_RESPAWN_COUNT));
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
     *   <li>Detaches OutboundSender VT (joins for up to 100ms — Plan 06 detach contract).</li>
     *   <li>Sends E|408|reconnect-required OUT-OF-BAND (direct sendMessage, post-detach).</li>
     *   <li>Closes the WS with SERVICE_RESTARTED to force client reconnect flow.</li>
     * </ol>
     *
     * <p>Steps 4–5 ordering is critical: detach first so the sender VT has stopped writing,
     * then the inbound thread can safely call sendMessage directly.
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

        // Detach sender VT FIRST (joins for up to 100ms per Plan 06).
        if (outboundSender != null) {
            outboundSender.detachSession(session.getId());
        }

        // Now safe to write directly: VT has joined, no concurrent writer.
        sendOutOfBand(session, new Frame.ErrorFrame(408, Optional.of(RejectionToken.RECONNECT_REQUIRED)));

        // Close the WS to force client into reconnect/resume flow.
        try { session.close(CloseStatus.SERVICE_RESTARTED); } catch (IOException ignored) {}
    }

    // ── Cleanup helpers ──────────────────────────────────────────────────────

    /**
     * Phase 17 D-12: reap an entity by ID. Invoked by {@link ResumeTokenRegistry} sweep when
     * grace expires. Resolves entityId → sessionId via {@link BotRegistry#getSessionByEntity},
     * then runs standard cleanup. Idempotent: if entityId is unknown, this is a no-op.
     */
    public void cleanupByEntityId(String entityId) {
        if (entityId == null) return;
        // Phase 17: terminal dropout — STALLED token expired before reconnect.
        // Operator SLI: rising counter indicates either widespread slow-consumer conditions
        // or grace-window mis-tuning.
        if (admissionMetrics != null) admissionMetrics.incTerminalDropout();
        String sessionId = botRegistry.getSessionByEntity(entityId).orElse(null);
        if (sessionId == null) {
            log.debug("cleanupByEntityId: entityId={} has no bound session (already reaped?)", entityId);
            // No session: decrement both buckets via snapshot (STALLED entity already gone).
            if (admissionMetrics != null) {
                io.micrometer.core.instrument.Tags bucketTags =
                        admissionMetrics.lookupBucketTags(entityId);
                if (bucketTags != null) {
                    admissionMetrics.decActiveBucketByTags(bucketTags);
                    admissionMetrics.decStalledBucketByTags(bucketTags);
                }
            }
            respawnCountAtStall.remove(entityId);
            return;
        }
        // Session exists: decrement stalled bucket via snapshot; cleanupBot handles active decrement.
        if (admissionMetrics != null) {
            io.micrometer.core.instrument.Tags bucketTags =
                    admissionMetrics.lookupBucketTags(entityId);
            if (bucketTags != null) {
                admissionMetrics.decStalledBucketByTags(bucketTags);
            }
        }
        respawnCountAtStall.remove(entityId);
        cleanupBot(sessionId);
    }

    /** Remove bot's entity from the grid, then unregister from BotRegistry. */
    public void cleanupBot(String sessionId) {
        WebSocketSession s = sessionRegistry.getSession(sessionId);
        boolean wasRegistered = false;
        if (s != null) {
            Object eid = s.getAttributes().remove(ATTR_ENTITY_ID);
            s.getAttributes().remove(ATTR_STALL_TICK);
            s.getAttributes().remove(ATTR_RESUME_TOKEN);
            // ATTR_ENTITY_TYPE is the durable "session ever admitted" marker (survives Phase 15.2
            // death-pivot). Removing here makes the slot release idempotent against repeated
            // cleanupBot calls for the same session.
            wasRegistered = s.getAttributes().remove(ATTR_ENTITY_TYPE) != null;
            if (eid instanceof String e) {
                respawnCountAtStall.remove(e);
                if (resumeTokenRegistry != null) resumeTokenRegistry.clearActive(e);
            }
        }
        botRegistry.getBySession(sessionId).ifPresent(state -> {
            var pos = state.position();
            worldGrid.clearEntity(pos.x(), pos.y());
        });
        botRegistry.unregisterBySession(sessionId);
        // Release the reservation booked at initial admission. Covers Alive→close, Dead→close,
        // and STALLED→reaped paths — once per registered session.
        if (wasRegistered && admissionGate != null) {
            admissionGate.releaseSlot();
        }
        // Per-bucket decrement (Phase 18 D-12): replaces scalar setActiveEntities.
        // Guarded by wasRegistered to prevent double-decrement on repeated cleanupBot calls
        // (handleTransportError → afterConnectionClosed idempotency).
        if (wasRegistered && admissionMetrics != null) {
            admissionMetrics.decActiveBucket(s);
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
     * Safe to call AFTER outboundSender.detachSession completes its 100ms join.
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
        session.getAttributes().remove(ATTR_ENTITY_ID);
    }
}
