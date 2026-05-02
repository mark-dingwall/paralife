package com.paralife.engine;

import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps WebSocket sessions to their controlled entities and grid positions.
 * Thread-safe — all operations are lock-free via ConcurrentHashMap.
 *
 * <p>Each session controls exactly one entity. The registry tracks:
 * <ul>
 *   <li>sessionId → BotState (entityId, position)</li>
 *   <li>entityId → sessionId (reverse lookup for death cleanup)</li>
 * </ul>
 */
@Component
public class BotRegistry {

    private static final Logger log = LoggerFactory.getLogger(BotRegistry.class);

    /**
     * Immutable state for a registered bot.
     */
    public record BotState(String sessionId, String entityId, Position position) {

        /**
         * Phase 17 D-13: returns a copy of this state with the sessionId replaced.
         * Used by {@link BotRegistry#rebindSession} to update the bySession map.
         */
        public BotState withSessionId(String newSessionId) {
            return new BotState(newSessionId, entityId, position);
        }
    }

    /**
     * Phase 15.2: death notice captured at the moment a bot-controlled entity
     * is unregistered due to death. Drained by {@code TickBroadcaster.onTick}
     * so each dead bot's session receives a terminal {@code vD} (SCHEMA §8.4
     * Died) frame before its entry is gone. Session stays open; client's
     * respawn FSM kicks off on receipt.
     */
    public record DeathNotice(String sessionId, String entityId, Position position) {}

    private final ConcurrentHashMap<String, BotState> bySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> entityToSession = new ConcurrentHashMap<>();
    private final List<DeathNotice> deathsThisTick = Collections.synchronizedList(new ArrayList<>());

    /**
     * Register a bot — associate a session with an entity at a position.
     */
    public void register(String sessionId, String entityId, Position position) {
        var state = new BotState(sessionId, entityId, position);
        bySession.put(sessionId, state);
        entityToSession.put(entityId, sessionId);
        log.debug("Bot registered: session={} entity={} pos={}", sessionId, entityId, position);
    }

    /**
     * Update a bot's position (e.g. after a move action).
     */
    public void updatePosition(String sessionId, Position newPosition) {
        bySession.computeIfPresent(sessionId, (k, old) -> {
            log.debug("Bot moved: session={} {} → {}", sessionId, old.position, newPosition);
            return new BotState(old.sessionId, old.entityId, newPosition);
        });
    }

    /**
     * Unregister a bot by session ID (e.g. on disconnect).
     */
    public void unregisterBySession(String sessionId) {
        var removed = bySession.remove(sessionId);
        if (removed != null) {
            entityToSession.remove(removed.entityId);
            log.debug("Bot unregistered (session): session={} entity={}", sessionId, removed.entityId);
        }
    }

    /**
     * Unregister a bot by entity ID (e.g. on entity death).
     */
    public void unregisterByEntity(String entityId) {
        var sessionId = entityToSession.remove(entityId);
        if (sessionId != null) {
            var prior = bySession.remove(sessionId);
            if (prior != null) {
                // Phase 15.2: queue death notice so TickBroadcaster can emit the
                // terminal vD frame on the next tick. Keeps the session open;
                // only the entity-binding is gone.
                deathsThisTick.add(new DeathNotice(sessionId, entityId, prior.position()));
            }
            log.debug("Bot unregistered (entity death): entity={} session={}", entityId, sessionId);
        }
    }

    /**
     * Phase 15.2: drain death notices captured since the last call. Called by
     * {@code TickBroadcaster.onTick} at the top of the broadcast step. Returns
     * an empty list when no deaths occurred.
     */
    public List<DeathNotice> drainDeaths() {
        synchronized (deathsThisTick) {
            if (deathsThisTick.isEmpty()) return List.of();
            var copy = new ArrayList<>(deathsThisTick);
            deathsThisTick.clear();
            return copy;
        }
    }

    /**
     * Remap a bot's entity ID (e.g., when BondedPair entity becomes CompositeMember).
     * Preserves the session but updates entity mapping.
     */
    public void remapEntity(String sessionId, String newEntityId, Position position) {
        var old = bySession.get(sessionId);
        if (old != null) {
            entityToSession.remove(old.entityId());
        }
        var state = new BotState(sessionId, newEntityId, position);
        bySession.put(sessionId, state);
        entityToSession.put(newEntityId, sessionId);
        log.debug("Bot remapped: session={} newEntity={} pos={}", sessionId, newEntityId, position);
    }

    /**
     * Phase 19.5 H2: remap a bot's entity ID for the predator's surviving session
     * at bond formation. Preserves the position from the existing BotState (the
     * BondedPair occupies the predator's primary cell). No-op if the session has
     * no prior registration (predator may already have disconnected).
     *
     * <p>WS:entity 1:1 (CLAUDE.md Phase 18 D-05/D-21): exactly one session controls
     * the resulting BondedPair — the predator's. Prey's session, if present, must
     * have been unregistered separately by the caller.
     */
    public void remapEntity(String sessionId, String newEntityId) {
        var old = bySession.get(sessionId);
        if (old == null) return;
        entityToSession.remove(old.entityId());
        var state = new BotState(sessionId, newEntityId, old.position());
        bySession.put(sessionId, state);
        entityToSession.put(newEntityId, sessionId);
        log.debug("Bot remapped (predator-survives): session={} newEntity={} pos={}",
                sessionId, newEntityId, old.position());
    }

    /**
     * Get the bot state for a session, if registered.
     */
    public Optional<BotState> getBySession(String sessionId) {
        return Optional.ofNullable(bySession.get(sessionId));
    }

    /**
     * Get the session ID controlling a given entity.
     */
    public Optional<String> getSessionForEntity(String entityId) {
        return Optional.ofNullable(entityToSession.get(entityId));
    }

    /**
     * Phase 17 D-12: alias for {@link #getSessionForEntity} used by
     * {@code WorldWebSocketHandler.cleanupByEntityId} (plan spec name).
     */
    public Optional<String> getSessionByEntity(String entityId) {
        return getSessionForEntity(entityId);
    }

    /**
     * Phase 17 D-13: rebind an existing entity (preserved across STALLED-pivot) to a new
     * WebSocket session. Removes the prior session→entity record (if any) and the prior
     * entity→session reverse record, then inserts the new session→entity record using the
     * SAME BotState (position, species, etc.) — only the session id changes.
     *
     * <p>If {@code entityId} is unknown, this is a no-op (returns false). Otherwise returns true.
     *
     * @throws IllegalStateException if the new sessionId is already bound to a different entity
     */
    public synchronized boolean rebindSession(String newSessionId, String entityId) {
        Objects.requireNonNull(newSessionId, "newSessionId");
        Objects.requireNonNull(entityId, "entityId");
        String oldSessionId = entityToSession.get(entityId);
        if (oldSessionId == null) return false;

        BotState existing = bySession.get(oldSessionId);
        if (existing == null) return false;

        // Defensive: refuse to clobber a different entity that may already be bound to newSessionId.
        BotState collision = bySession.get(newSessionId);
        if (collision != null && !collision.entityId().equals(entityId)) {
            throw new IllegalStateException("rebindSession: " + newSessionId
                    + " already bound to entity=" + collision.entityId()
                    + "; refusing to clobber for entity=" + entityId);
        }

        bySession.remove(oldSessionId);
        bySession.put(newSessionId, existing.withSessionId(newSessionId));
        entityToSession.put(entityId, newSessionId);
        log.debug("Bot rebound: entity={} oldSession={} newSession={}", entityId, oldSessionId, newSessionId);
        return true;
    }

    /**
     * All currently registered bots.
     */
    public Collection<BotState> getAllBots() {
        return bySession.values();
    }

    /**
     * Number of registered bots.
     */
    public int size() {
        return bySession.size();
    }

    /**
     * Clear all registrations (for testing).
     *
     * <p>Phase 19.5: also drains {@link #deathsThisTick}. Prior to this fix, a bond
     * formation that queued a death notice via {@link #unregisterByEntity} could
     * leak across {@code resetAll()} boundaries in
     * {@code GoldenTraceEquivalenceTest} — the next run drained a stale notice on
     * its first tick, producing an off-by-one emit count vs run 1 (intermittent
     * because timing of when the broadcaster drained it varied).
     */
    public void clear() {
        bySession.clear();
        entityToSession.clear();
        synchronized (deathsThisTick) {
            deathsThisTick.clear();
        }
    }
}
