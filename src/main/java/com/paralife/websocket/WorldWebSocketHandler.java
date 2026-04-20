package com.paralife.websocket;

import com.paralife.codec.CodecException;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.ActionResolver;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.MetabolicProfile;
import com.paralife.engine.TickEngine;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Plan 15-06 — codec-driven WebSocket handler.
 *
 * <p>All wire I/O runs through {@link PerceptionCodec}. There is no Jackson on
 * the inbound or outbound hot path. Inbound text messages are decoded as
 * {@link Frame}; outbound responses are encoded back to the compact wire
 * format per {@code 15-SCHEMA.md}.
 *
 * <p><b>Session FSM (D-33).</b> Each session carries a two-state life cycle:
 * <ul>
 *   <li><b>Unregistered</b> (initial) — {@code entityId} attribute null. Only
 *       {@code r|} (RegisterFrame) is accepted; everything else responds with
 *       {@code E|404|no active entity}.</li>
 *   <li><b>Alive</b> — {@code entityId} non-null. Action frames
 *       ({@code a|…}) are queued against the resolver. A second {@code r|}
 *       is rejected {@code E|409|already registered}.</li>
 *   <li><b>Dead (respawn pending)</b> — reached when a downstream component
 *       (plan 15-08 tick broadcaster) calls {@link #markDead(WebSocketSession)}.
 *       The session's {@code entityId} attribute is cleared; {@code entityType}
 *       survives so the client does not have to re-specify. A subsequent
 *       {@code r|} re-registers, counted by {@code respawnCount}.</li>
 * </ul>
 *
 * <p><b>Respawn cap (T-15-04).</b> {@link #MAX_RESPAWNS_PER_SESSION} bounds
 * respawn storms per session. The first {@code r|} is registration, not
 * counted; subsequent {@code r|} accepts each increment {@code respawnCount}.
 * Exceeding the cap yields {@code E|429|respawn cap exceeded}. The session
 * itself stays open so a client can still observe the final error.
 */
@Component
public class WorldWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorldWebSocketHandler.class);

    /**
     * D-33 per-session respawn cap. Bounds the respawn-storm DoS vector
     * (T-15-04). Value is a reasonable default; exposing it as a configurable
     * property is deferred to a later plan.
     */
    private static final int MAX_RESPAWNS_PER_SESSION = 5;

    /** Max random-placement attempts before declaring the grid effectively full. */
    private static final int MAX_PLACEMENT_ATTEMPTS = 50;

    // Session attribute keys (D-33 FSM).
    private static final String ATTR_ENTITY_ID = "entityId";
    private static final String ATTR_ENTITY_TYPE = "entityType";
    private static final String ATTR_RESPAWN_COUNT = "respawnCount";

    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final TickEngine tickEngine;
    private final BotRegistry botRegistry;
    private final ActionResolver actionResolver;
    private final MetabolicProfile metabolicProfile;

    public WorldWebSocketHandler(SessionRegistry sessionRegistry, WorldGrid worldGrid,
                                  TickEngine tickEngine, BotRegistry botRegistry,
                                  ActionResolver actionResolver,
                                  MetabolicProfile metabolicProfile) {
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.tickEngine = tickEngine;
        this.botRegistry = botRegistry;
        this.actionResolver = actionResolver;
        this.metabolicProfile = metabolicProfile;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionRegistry.register(session);
        // Plan 15-06: no Welcome frame — the protocol no longer has one.
        // First `r|` from the client returns an `S|<entityId>` sync frame.
        log.info("Client connected: {} (total: {})", session.getId(), sessionRegistry.getSessionCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Frame frame;
        try {
            frame = PerceptionCodec.decode(message.getPayload());
        } catch (CodecException e) {
            log.warn("Malformed frame from {}: {}", session.getId(), e.getMessage());
            sendFrame(session, new Frame.ErrorFrame(400, Optional.of("Malformed frame")));
            return;
        }

        switch (frame) {
            case Frame.RegisterFrame r -> handleRegister(session, r);
            case Frame.ActionFrame a -> handleAction(session, a);
            case Frame.SyncFrame ignored -> sendFrame(session,
                    new Frame.ErrorFrame(400, Optional.of("Client cannot send S")));
            case Frame.TickFrame ignored -> sendFrame(session,
                    new Frame.ErrorFrame(400, Optional.of("Client cannot send T")));
            case Frame.ErrorFrame ignored -> { /* ignore client-sent errors */ }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanupBot(session.getId());
        sessionRegistry.unregister(session.getId());
        log.info("Client disconnected: {} (status: {}, total: {})",
                session.getId(), status, sessionRegistry.getSessionCount());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error for session {}: {}", session.getId(), exception.getMessage());
        cleanupBot(session.getId());
        sessionRegistry.unregister(session.getId());
    }

    /** Remove bot's entity from the grid, then unregister from BotRegistry. */
    private void cleanupBot(String sessionId) {
        botRegistry.getBySession(sessionId).ifPresent(state -> {
            var pos = state.position();
            worldGrid.clearEntity(pos.x(), pos.y());
        });
        botRegistry.unregisterBySession(sessionId);
    }

    private void handleRegister(WebSocketSession session, Frame.RegisterFrame register) {
        var attrs = session.getAttributes();

        // Alive? Second r| while alive is rejected (409).
        Object existingId = attrs.get(ATTR_ENTITY_ID);
        if (existingId != null) {
            sendFrame(session, new Frame.ErrorFrame(409, Optional.of("already registered")));
            return;
        }

        // Respawn cap gate — first r| is registration (not counted).
        int respawnCount = respawnCountOf(attrs);
        Object storedType = attrs.get(ATTR_ENTITY_TYPE);
        boolean isRespawn = storedType != null;
        if (isRespawn && respawnCount >= MAX_RESPAWNS_PER_SESSION) {
            sendFrame(session, new Frame.ErrorFrame(429, Optional.of("respawn cap exceeded")));
            return;
        }

        // Resolve ParticleType. Either first-time from frame, or reuse stored.
        ParticleType particleType;
        if (isRespawn) {
            particleType = switch ((Character) storedType) {
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

        var rng = ThreadLocalRandom.current();
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
            sendFrame(session, new Frame.ErrorFrame(503, Optional.of("GRID_FULL")));
            return;
        }

        botRegistry.register(session.getId(), entityId, new Position(x, y));

        // Update FSM attributes.
        attrs.put(ATTR_ENTITY_ID, entityId);
        if (!isRespawn) {
            attrs.put(ATTR_ENTITY_TYPE, register.entityType());
        } else {
            attrs.put(ATTR_RESPAWN_COUNT, respawnCount + 1);
        }

        sendFrame(session, new Frame.SyncFrame(entityId, List.of()));
        log.info("Entity registered: {} at ({},{}) type={} respawnCount={}",
                entityId, x, y, particleType, attrs.get(ATTR_RESPAWN_COUNT));
    }

    private void handleAction(WebSocketSession session, Frame.ActionFrame action) {
        Object entityId = session.getAttributes().get(ATTR_ENTITY_ID);
        if (entityId == null) {
            sendFrame(session, new Frame.ErrorFrame(404, Optional.of("no active entity")));
            return;
        }
        actionResolver.queueAction(session.getId(), action);
        log.debug("Action queued from {}: verb={}", session.getId(), action.verb());
    }

    private static int respawnCountOf(java.util.Map<String, Object> attrs) {
        Object v = attrs.get(ATTR_RESPAWN_COUNT);
        if (v instanceof Integer i) return i;
        return 0;
    }

    // ── Outbound ─────────────────────────────────────────────────

    /**
     * Encode {@code frame} via {@link PerceptionCodec#encode} and push over the
     * socket. Sends are guarded by {@code synchronized (session)} to respect
     * the single-writer-per-session invariant of the Spring WebSocket API.
     */
    void sendFrame(WebSocketSession session, Frame frame) {
        if (session == null || !session.isOpen()) return;
        try {
            String encoded = PerceptionCodec.encode(frame);
            synchronized (session) {
                session.sendMessage(new TextMessage(encoded));
            }
        } catch (Exception e) {
            log.warn("Failed to send frame to {}: {}", session.getId(), e.getMessage());
        }
    }

    /** Convenience wrapper for send-error-frame callers. */
    public void sendErrorFrame(WebSocketSession session, int code, String msg) {
        sendFrame(session, new Frame.ErrorFrame(code, Optional.ofNullable(msg)));
    }

    /**
     * Transition the session from Alive to Dead (respawn pending). Called by
     * the downstream broadcaster (plan 15-08) when it detects the session's
     * entity has been removed from the grid. Subsequent {@code r|} is accepted
     * as a respawn, counted against {@link #MAX_RESPAWNS_PER_SESSION}.
     */
    public void markDead(WebSocketSession session) {
        if (session == null) return;
        session.getAttributes().put(ATTR_ENTITY_ID, null);
    }
}
