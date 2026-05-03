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
 * <p><b>Session attribution (Phase 19.5 M6, post review-remediation):</b> this
 * registry intentionally holds NO session attribution. {@link BotRegistry}
 * remains the authoritative source for session→entity routing. The prior
 * {@code Optional<String> sessionId} field on {@link EntityEntry} was deleted
 * — it was populated only at {@code WorldWebSocketHandler.handleRegister} and
 * was {@code Optional.empty()} at all server-internal creation sites (bonding,
 * composite formation, reproduce-children, collapse, dissolve, revert). Rebind
 * never refreshed it. Phase 20.1 will introduce a session-mapping side channel
 * for the broadcaster migration — design TBD as the FIRST task of that phase,
 * before the broadcaster migration itself.
 *
 * <p><b>Re-register policy (REVIEWS MEDIUM-3):</b> {@link #register} is
 * idempotent on identical re-register (same position) but throws
 * {@link IllegalStateException} on conflicting re-register (different
 * position for an already-registered entityId). Defence in depth against
 * silently-dropped lifecycle hooks. Callers must {@link #unregister} first
 * if they intend to change identity.
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
     * Phase 19.5 M6: two-arg shape — sessionId field deleted (see class Javadoc).
     */
    public record EntityEntry(String entityId, Position position) {
        public EntityEntry withPosition(Position newPosition) {
            return new EntityEntry(entityId, newPosition);
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
     *
     * <p>Phase 19.5 M6: sessionId param deleted. Callers that previously passed
     * {@code Optional.of(sessionId)} should rely on {@link BotRegistry} for the
     * session→entity mapping; callers that passed {@code Optional.empty()}
     * (server-internal creations) drop the third arg entirely.
     */
    public synchronized void register(String entityId, Position position) {
        // Phase 19.5 multi-review M-G note: this method keys only on entityId, so
        // two callers racing to register DIFFERENT entityIds at the SAME position
        // would both succeed, leaving the registry briefly inconsistent with the
        // grid (which permits only one occupant per cell). The race window is
        // narrow — in practice register() callers hold the WS thread (handleRegister)
        // or the tick thread (bond/composite formation) and the grid's own occupied
        // check serialises real placement. Atomic check-and-place against the grid
        // is deferred to Phase 20 connection-multiplexing design (where N concurrent
        // registrations per JVM materially increases the race surface).
        Integer existing = indexById.get(entityId);
        if (existing != null) {
            EntityEntry prior = dense.get(existing);
            if (Objects.equals(prior.position(), position)) {
                return; // idempotent
            }
            throw new IllegalStateException(
                "Conflicting re-register for entityId=" + entityId
                    + ": prior=" + prior + " new=(pos=" + position
                    + ") — caller must unregister first");
        }
        indexById.put(entityId, dense.size());
        dense.add(new EntityEntry(entityId, position));
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
     *
     * <p><b>Per-tick call frequency (P19.5 multi-review pass 2 / IN-03):</b> this
     * is invoked ~8×/tick across SimulationEngine + EnvironmentEngine + ActionResolver.
     * A pre-compute-once-per-tick cached snapshot would eliminate the redundant
     * sort cost. Deferred to Phase 21 perf pass — the snapshot would need a
     * lifecycle-listener-driven invalidation and the current cost (~80µs/tick at
     * N=1000) is well below the per-tick budget. Do NOT cache here without the
     * invalidation hook; stale snapshots would silently violate the live-registry
     * invariant.
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
