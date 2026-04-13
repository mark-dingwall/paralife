package com.paralife.engine;

import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry for composite organism shared state.
 * Thread-safe for concurrent reads (WebSocket, PerceptionBroadcaster).
 * Mutations happen in the single-threaded tick pipeline.
 *
 * <p>Tracks:
 * <ul>
 *   <li>compositeId -> CompositeState (members, positions, energy pool)</li>
 *   <li>memberId -> compositeId (reverse lookup for engine)</li>
 * </ul>
 */
@Component
public class CompositeRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompositeRegistry.class);

    private final ConcurrentHashMap<String, CompositeState> composites = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> memberToComposite = new ConcurrentHashMap<>();

    /**
     * Mutable composite state. Tick pipeline is single-threaded for mutations;
     * CopyOnWriteArrayList and AtomicInteger provide safe concurrent reads
     * (e.g., from PerceptionBroadcaster on WebSocket threads).
     */
    public static class CompositeState {

        private final String compositeId;
        private final CopyOnWriteArrayList<String> memberIds;
        private final ConcurrentHashMap<String, Position> memberPositions;
        private final AtomicInteger sharedPoolEnergy;
        private final int maxPoolEnergy;

        public CompositeState(String compositeId, List<String> memberIds,
                              Map<String, Position> memberPositions,
                              int sharedPoolEnergy, int maxPoolEnergy) {
            this.compositeId = compositeId;
            this.memberIds = new CopyOnWriteArrayList<>(memberIds);
            this.memberPositions = new ConcurrentHashMap<>(memberPositions);
            this.sharedPoolEnergy = new AtomicInteger(sharedPoolEnergy);
            this.maxPoolEnergy = maxPoolEnergy;
        }

        public String getCompositeId() {
            return compositeId;
        }

        /** Returns an unmodifiable view of member IDs. */
        public List<String> getMemberIds() {
            return Collections.unmodifiableList(memberIds);
        }

        public int getSharedPoolEnergy() {
            return sharedPoolEnergy.get();
        }

        public int getMaxPoolEnergy() {
            return maxPoolEnergy;
        }

        /** Returns position for a member, or null if unknown. */
        public Position getPositionForMember(String memberId) {
            return memberPositions.get(memberId);
        }

        /** Update a single member's position. */
        public void setMemberPosition(String memberId, Position pos) {
            memberPositions.put(memberId, pos);
        }

        /** Replace all member positions atomically (used after rigid-body movement). */
        public void updateAllPositions(Map<String, Position> positions) {
            memberPositions.clear();
            memberPositions.putAll(positions);
        }

        /** Add a new member with its initial position. */
        public void addMember(String memberId, Position pos) {
            memberIds.add(memberId);
            memberPositions.put(memberId, pos);
        }

        /** Remove a member and its position entry. */
        public void removeMember(String memberId) {
            memberIds.remove(memberId);
            memberPositions.remove(memberId);
        }

        /** Add energy to the shared pool. */
        public void addEnergy(int amount) {
            sharedPoolEnergy.addAndGet(amount);
        }

        /**
         * Drain energy from the shared pool.
         * Returns the actual amount drained (clamped to available energy).
         */
        public int drainEnergy(int amount) {
            int current = sharedPoolEnergy.get();
            int actual = Math.min(current, amount);
            sharedPoolEnergy.addAndGet(-actual);
            return actual;
        }

        public int getMemberCount() {
            return memberIds.size();
        }
    }

    // ── Registry operations ───────────────────────────────────────

    /**
     * Register a new composite with initial members, positions, and energy pool.
     */
    public void register(String compositeId, List<String> memberIds,
                         Map<String, Position> memberPositions,
                         int sharedPoolEnergy, int maxPoolEnergy) {
        var state = new CompositeState(compositeId, memberIds, memberPositions,
                sharedPoolEnergy, maxPoolEnergy);
        composites.put(compositeId, state);
        for (String memberId : memberIds) {
            memberToComposite.put(memberId, compositeId);
        }
        log.debug("Composite registered: id={} members={} pool={}/{}",
                compositeId, memberIds.size(), sharedPoolEnergy, maxPoolEnergy);
    }

    /**
     * Get the composite state for a given composite ID.
     */
    public Optional<CompositeState> getComposite(String compositeId) {
        return Optional.ofNullable(composites.get(compositeId));
    }

    /**
     * Get the composite ID for a member entity.
     */
    public Optional<String> getCompositeForMember(String memberId) {
        return Optional.ofNullable(memberToComposite.get(memberId));
    }

    /**
     * Get the position for a member entity.
     * Used by Plans 02/04/05 for formation, combat, and perception stitching.
     *
     * @return the member's position, or null if unknown
     */
    public Position getPositionForMember(String memberId) {
        String compositeId = memberToComposite.get(memberId);
        if (compositeId == null) return null;
        CompositeState state = composites.get(compositeId);
        if (state == null) return null;
        return state.getPositionForMember(memberId);
    }

    /**
     * Bulk-update all member positions for a composite.
     * Used by Plan 03 after rigid-body movement resolves.
     */
    public void updateMemberPositions(String compositeId, Map<String, Position> positions) {
        CompositeState state = composites.get(compositeId);
        if (state != null) {
            state.updateAllPositions(positions);
        }
    }

    /**
     * Remove a member from a composite. Also removes position entry and reverse lookup.
     */
    public void removeMember(String compositeId, String memberId) {
        CompositeState state = composites.get(compositeId);
        if (state != null) {
            state.removeMember(memberId);
        }
        memberToComposite.remove(memberId);
        log.debug("Member removed from composite: member={} composite={}", memberId, compositeId);
    }

    /**
     * Dissolve a composite entirely — removes all member reverse lookups and the composite entry.
     */
    public void dissolve(String compositeId) {
        CompositeState state = composites.remove(compositeId);
        if (state != null) {
            for (String memberId : state.getMemberIds()) {
                memberToComposite.remove(memberId);
            }
            log.debug("Composite dissolved: id={} members={}", compositeId, state.getMemberCount());
        }
    }

    /**
     * All active composites.
     */
    public Collection<CompositeState> getAll() {
        return composites.values();
    }

    /**
     * Number of active composites.
     */
    public int size() {
        return composites.size();
    }

    /**
     * Clear all entries (for testing).
     */
    public void clear() {
        composites.clear();
        memberToComposite.clear();
    }

    /**
     * Add energy to a composite's shared pool.
     */
    public void addEnergy(String compositeId, int amount) {
        CompositeState state = composites.get(compositeId);
        if (state != null) {
            state.addEnergy(amount);
        }
    }

    /**
     * Drain energy from a composite's shared pool.
     *
     * @return actual amount drained (clamped to available energy)
     */
    public int drainEnergy(String compositeId, int amount) {
        CompositeState state = composites.get(compositeId);
        if (state == null) return 0;
        return state.drainEnergy(amount);
    }

    /**
     * Get the current shared pool energy for a composite.
     */
    public int getSharedEnergy(String compositeId) {
        CompositeState state = composites.get(compositeId);
        return state != null ? state.getSharedPoolEnergy() : 0;
    }

    /**
     * Get the member count for a composite.
     */
    public int getMemberCount(String compositeId) {
        CompositeState state = composites.get(compositeId);
        return state != null ? state.getMemberCount() : 0;
    }
}
