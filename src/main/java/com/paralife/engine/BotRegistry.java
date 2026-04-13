package com.paralife.engine;

import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
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
    public record BotState(String sessionId, String entityId, Position position) {}

    private final ConcurrentHashMap<String, BotState> bySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> entityToSession = new ConcurrentHashMap<>();

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
            bySession.remove(sessionId);
            log.debug("Bot unregistered (entity death): entity={} session={}", entityId, sessionId);
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
     */
    public void clear() {
        bySession.clear();
        entityToSession.clear();
    }
}
