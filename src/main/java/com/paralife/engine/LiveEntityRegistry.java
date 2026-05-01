package com.paralife.engine;

import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 19 SCALE-07 (D-07..D-11): authoritative list of live entities for
 * tick-handler iteration. Replaces the O(width*height) grid scans used today by
 * {@code SimulationEngine} and {@code EnvironmentEngine} per-entity segments.
 * Plan 04 consumes this bean for those two callers; <b>TickBroadcaster keeps
 * {@code botRegistry.getAllBots()} in Phase 19</b> per REVIEWS CONSENSUS-H1 OPTION B
 * (USER-LOCKED; broadcaster migration deferred to Phase 20.1+).
 *
 * <p>Sparse-set: dense ArrayList of EntityEntry + HashMap entityId→index.
 * O(1) register, unregister (swap-and-pop), updatePosition.
 *
 * <p><b>Iteration order — REVIEWS HIGH-1 (consensus of all four reviewers):</b>
 * {@link #snapshot()} returns a shallow copy SORTED BY ROW-MAJOR LINEAR INDEX
 * {@code position.x() * height + position.y()}. This matches the pre-Plan-04
 * grid-scan order ({@code for (x){ for (y){ } }}). {@link
 * java.util.Collections#shuffle} is deterministic given (input order, seed);
 * preserving row-major input order across the Plan 04 cut keeps shuffle output
 * byte-identical → same combat resolution → same per-session digests in
 * {@code GoldenTraceEquivalenceTest}.
 *
 * <p><b>Phase 21 revisit (REVIEWS R2-14 OpenCode):</b> the row-major sort is a
 * Phase 19 compatibility shim; cost is negligible at N≤256 (~µs) and ~10µs at
 * N=1000. Phase 21 may revisit if a different ordering improves cache behaviour.
 *
 * <p><b>Composite/bonded identity — REVIEWS CONSENSUS-H1 OPTION B (USER-LOCKED):</b>
 * {@link EntityEntry#sessionId()} is {@code Optional.of(sessionId)} when an
 * entity is registered via {@code WorldWebSocketHandler.handleRegister} (one
 * bot ↔ one session ↔ one entity at admission). All server-internal creations
 * (bonding, composite formation, reproduce-children, collapse, dissolve,
 * revert) use {@code Optional.empty()}. Composite and bonded child entityIds
 * are NOT separately registered — only the grid-occupant entity (BondedPair,
 * CompositeMember) is.
 *
 * <p><b>TickBroadcaster does NOT consume sessionId in Phase 19.</b> The field
 * is reserved for Phase 20.1+ broadcaster migration. Phase 19 broadcaster
 * iteration continues via {@code botRegistry.getAllBots()}.
 *
 * <p><b>Re-register policy (REVIEWS MEDIUM-3):</b> {@link #register} is
 * idempotent on identical re-register but throws {@link IllegalStateException}
 * on conflicting re-register (different position or different sessionId for an
 * already-registered entityId). Defence in depth against silently-dropped
 * lifecycle hooks. Callers must {@link #unregister} first if they intend to
 * change identity.
 *
 * <p>Single-threaded mutation invariant (D-08, D-11) is unaffected: this
 * registry is read by tick handlers, written from registration (WS thread),
 * death (tick thread), composite collapse (tick thread), movement (tick
 * thread). All public methods synchronize on this bean. NO parallelStream.
 */
@Component
public class LiveEntityRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveEntityRegistry.class);

    /**
     * REVIEWS L2 — Optional in record is intentional (server-internal record;
     * not serialised over the wire; no equals concerns from Optional).
     */
    public record EntityEntry(String entityId, Position position, Optional<String> sessionId) {
        public EntityEntry withPosition(Position newPosition) {
            return new EntityEntry(entityId, newPosition, sessionId);
        }
    }

    private final int height;
    private final List<EntityEntry> dense = new ArrayList<>();
    private final Map<String, Integer> indexById = new HashMap<>();
    private final Comparator<EntityEntry> rowMajorComparator;

    public LiveEntityRegistry(GridConfig gridConfig) {
        this.height = gridConfig.height();
        this.rowMajorComparator = Comparator.comparingInt(this::rowMajorIndex);
    }

    /**
     * Register an entity. Idempotent on identical inputs; throws
     * {@link IllegalStateException} on conflict. REVIEWS MEDIUM-3.
     */
    public synchronized void register(String entityId, Position position, Optional<String> sessionId) {
        Integer existing = indexById.get(entityId);
        if (existing != null) {
            EntityEntry prior = dense.get(existing);
            if (Objects.equals(prior.position(), position)
                    && Objects.equals(prior.sessionId(), sessionId)) {
                return; // idempotent
            }
            throw new IllegalStateException(
                "Conflicting re-register for entityId=" + entityId
                    + ": prior=" + prior + " new=(pos=" + position + ", sid=" + sessionId
                    + ") — caller must unregister first");
        }
        indexById.put(entityId, dense.size());
        dense.add(new EntityEntry(entityId, position, sessionId));
    }

    /** Row-major linear index: position().x() * height + position().y(). REVIEWS HIGH-1. */
    private int rowMajorIndex(EntityEntry e) {
        return e.position().x() * height + e.position().y();
    }

    /**
     * Remove an entity from the registry. O(1) swap-and-pop. Idempotent on missing id.
     */
    public synchronized void unregister(String entityId) {
        Integer idx = indexById.remove(entityId);
        if (idx == null) return;
        int last = dense.size() - 1;
        if (idx == last) {
            dense.remove(last);
        } else {
            EntityEntry tail = dense.remove(last);
            dense.set(idx, tail);
            indexById.put(tail.entityId(), idx);
        }
    }

    /**
     * Update position for a registered entity. O(1). Preserves sessionId.
     * Idempotent if entityId is missing.
     */
    public synchronized void updatePosition(String entityId, Position newPosition) {
        Integer idx = indexById.get(entityId);
        if (idx == null) return;
        dense.set(idx, dense.get(idx).withPosition(newPosition));
    }

    /**
     * O(N + N log N) shallow copy SORTED BY ROW-MAJOR LINEAR INDEX.
     * REVIEWS HIGH-1 / R2-14 — pre-Plan-04 grid-scan order compatibility shim;
     * cost ~10µs at N=1000. Phase 21 may revisit if a different ordering improves
     * cache behaviour.
     */
    public synchronized List<EntityEntry> snapshot() {
        List<EntityEntry> copy = new ArrayList<>(dense);
        copy.sort(rowMajorComparator);
        return copy;
    }

    /**
     * Current number of live entities in the registry.
     */
    public synchronized int size() {
        return dense.size();
    }

    /**
     * Clear all entries — for testing only.
     */
    public synchronized void clearForTest() {
        dense.clear();
        indexById.clear();
    }
}
