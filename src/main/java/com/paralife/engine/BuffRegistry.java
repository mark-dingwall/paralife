package com.paralife.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shadow registry for mutagen survivor buffs (D-15, D-19, D-47).
 *
 * <p>Maps entity id → list of active buffs with expiry ticks. Mirrors the
 * {@link BotRegistry} / {@link CompositeRegistry} shadow-registry pattern:
 * <ul>
 *   <li>Avoids bloating immutable Particle / BondedPair / CompositeMember records</li>
 *   <li>Entity identity preserved across the registry (keyed by entity id)</li>
 *   <li>{@code unregisterEntity} called on every death to prevent orphan state</li>
 * </ul>
 *
 * <p>Concurrency: {@link ConcurrentHashMap} for the outer map, per-entity
 * {@link CopyOnWriteArrayList} for the inner list so WebSocket perception
 * threads can read while the tick thread writes.
 *
 * <p><b>Dedup semantics (truth #2):</b> {@link #grant} dedupes the same
 * {@link BuffType} per entity — no numerical stacking. Existing buff of the
 * same type has its {@code expiryTick} replaced with {@code max(existing, new)}
 * so a shorter re-grant can never shrink an active longer buff.
 */
@Component
public class BuffRegistry {

    private static final Logger log = LoggerFactory.getLogger(BuffRegistry.class);

    /** Four mutagen survivor buffs (D-15). Single source of truth. */
    public enum BuffType {
        ATTACK_PLUS_1,      // +1 attack power
        MOVEMENT_PLUS_1,    // hop-to-range-2 enabled (reuses SPORE reproduce-range=2 code)
        SENSOR_PLUS_1,      // vision 5×5 → 7×7
        UPKEEP_MINUS_1      // decay rate -1 (or modulus-skip if decay already 1)
    }

    /** Immutable per-buff record. */
    public record ActiveBuff(BuffType type, long expiryTick) {}

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ActiveBuff>> byEntity =
            new ConcurrentHashMap<>();

    /**
     * Grant a buff to {@code entityId}, dedup'd by {@link BuffType}.
     *
     * <p>If an existing buff of the same type is present, its {@code expiryTick}
     * becomes {@code max(existing, expiryTick)} so the longer of the two wins.
     * Otherwise a new {@link ActiveBuff} is appended.
     */
    public void grant(String entityId, BuffType type, long expiryTick) {
        byEntity.compute(entityId, (key, existing) -> {
            CopyOnWriteArrayList<ActiveBuff> list =
                    existing != null ? existing : new CopyOnWriteArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                ActiveBuff b = list.get(i);
                if (b.type() == type) {
                    long newExpiry = Math.max(b.expiryTick(), expiryTick);
                    list.set(i, new ActiveBuff(type, newExpiry));
                    return list;
                }
            }
            list.add(new ActiveBuff(type, expiryTick));
            return list;
        });
        log.debug("Buff granted: entity={} type={} expiryTick={}", entityId, type, expiryTick);
    }

    /** Unmodifiable snapshot of an entity's active buffs (or empty list). */
    public List<ActiveBuff> getBuffs(String entityId) {
        List<ActiveBuff> list = byEntity.get(entityId);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /** Returns true if {@code entityId} has an active buff of the given type. */
    public boolean hasBuff(String entityId, BuffType type) {
        List<ActiveBuff> list = byEntity.get(entityId);
        if (list == null) return false;
        for (ActiveBuff b : list) {
            if (b.type() == type) return true;
        }
        return false;
    }

    /**
     * Sweep expired buffs across all entities. Called from
     * {@link EnvironmentEngine#onTick} each tick.
     *
     * @param currentTick tick number — any buff with {@code expiryTick <= currentTick}
     *                    is removed.
     */
    public void expireBuffs(long currentTick) {
        byEntity.entrySet().removeIf(e -> {
            CopyOnWriteArrayList<ActiveBuff> list = e.getValue();
            list.removeIf(b -> b.expiryTick() <= currentTick);
            return list.isEmpty();
        });
    }

    /**
     * Remove all buffs for {@code entityId} — called on entity death by
     * {@link DeathFinalizer} to prevent orphan buff state.
     */
    public void unregisterEntity(String entityId) {
        CopyOnWriteArrayList<ActiveBuff> removed = byEntity.remove(entityId);
        if (removed != null && !removed.isEmpty()) {
            log.debug("Buff registry entry removed for entity={} ({} active buffs dropped)",
                    entityId, removed.size());
        }
    }

    /** Clear all registrations (for testing). */
    public void clear() {
        byEntity.clear();
    }

    /**
     * Plan 14-03 cycle-6 HIGH #2 helper: transfer all active buffs from
     * {@code fromId} to {@code toId}. Used by SimulationEngine at
     * identity-transition sites (BondFormation, revertToBondedPair). After
     * this call, {@code fromId} has no buffs.
     *
     * <p>Merge semantics: if {@code toId} already has a buff of the same type,
     * the existing {@link #grant} dedup keeps the MAX {@code expiryTick}.
     * Different types concatenate.
     *
     * <p>No-op if {@code fromId} has no buffs or if {@code fromId == toId}.
     */
    public void transferBuffs(String fromId, String toId) {
        if (fromId == null || toId == null || fromId.equals(toId)) return;
        List<ActiveBuff> srcBuffs = byEntity.get(fromId);
        if (srcBuffs == null || srcBuffs.isEmpty()) return;
        // Snapshot to avoid concurrent modification during grant() calls.
        List<ActiveBuff> snap = new ArrayList<>(srcBuffs);
        for (ActiveBuff b : snap) {
            grant(toId, b.type(), b.expiryTick());
        }
        unregisterEntity(fromId);
    }

    /** Number of entities with at least one active buff. */
    public int size() {
        return byEntity.size();
    }

    /**
     * Plan 14-06 Task 3b (cycle-9 action D): returns a snapshot of the currently
     * registered entity ids so the phase-gate integration test can sum active
     * buffs across all entities. Returned set is a live view backed by the
     * underlying {@link ConcurrentHashMap#keySet()} — safe to iterate, do not
     * mutate.
     */
    public java.util.Set<String> getRegisteredEntityIds() {
        return byEntity.keySet();
    }
}
