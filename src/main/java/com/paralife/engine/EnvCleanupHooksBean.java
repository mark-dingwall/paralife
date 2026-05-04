package com.paralife.engine;

import com.paralife.world.Entity;
import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Third-bean Spring component that implements {@link DeathCleanupHooks}
 * (cycle-4 action item #1).
 *
 * <p>Owns the canonical env-state maps that BOTH EnvironmentEngine and
 * DeathFinalizer need to read/write:
 * <ul>
 *   <li>{@code infections} — keyed by entity id (or BondedPair id, per 14-03)</li>
 *   <li>{@code cureImmuneUntil} — post-cure grace-period bookkeeping</li>
 *   <li>{@code pendingBuffGrants} — post-damage-alive-gated buff-grant queue</li>
 * </ul>
 *
 * <p>Plan 14-03 Task 2: typed containers replace the Plan 01 {@code Object}-keyed
 * placeholders — {@code Map<String, Infection>} and {@code List<PendingGrant>}.
 * Public accessors allow {@link EnvironmentEngine} to operate on shared state
 * without duplicating storage (cycle-4 action item #1).
 *
 * <p>Compost application is delegated: EnvironmentEngine implements
 * {@link CompostSink} and registers itself on this bean via a
 * {@code @PostConstruct} call.
 *
 * <p>cycle-9 action A: implements {@link ApplicationListener} on
 * {@link ContextRefreshedEvent} so a missing CompostSink registration is
 * caught loudly at context-refresh time rather than silently no-op'ing
 * compost writes in production.
 */
@Component
public class EnvCleanupHooksBean implements DeathCleanupHooks,
        ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(EnvCleanupHooksBean.class);

    /** Setter-injected collaborator that actually mutates the grid's nutrient levels. */
    public interface CompostSink {
        void applyCompost(Position deathPos);
    }

    /**
     * Plan 14-03: post-damage-gated buff-grant queue record. Carries its own
     * {@link Position} so {@code tickBuffsAndInfections} Phase B can look up
     * the occupant at the captured position rather than via the
     * {@code infections} map (T-14-03-11 cure-path bug fix).
     *
     * @param entityId       id of the entity to grant a buff to (Particle,
     *                       BondedPair, or CompositeMember)
     * @param initialTicks   original infection duration — feeds the buff-duration
     *                       multiplier at grant time
     * @param capturedOccupant snapshot of the occupant at enqueue time
     *                       (useful for composite-member role lookup)
     * @param position       cell position captured at enqueue time
     */
    // Tie-totality is invariant-guaranteed (see EnvironmentEngine.reduceInfection early-return).
    // If a third enqueue site lands, add a long sourceEventId field and append
    // .thenComparingLong to the comparator (pass-5 triage 2026-05-04).
    public record PendingGrant(String entityId, int initialTicks,
                                Entity capturedOccupant, Position position) {}

    /**
     * cycle-9 action A — fail-fast on missing CompostSink.
     *
     * <p>Spring does not guarantee {@code @PostConstruct} ordering between unrelated
     * beans. {@link EnvironmentEngine} registers itself as the sink in its
     * {@code @PostConstruct}; if that callback runs BEFORE the ApplicationContext
     * finishes refreshing and some future refactor drops the ordering, this bean
     * would silently no-op on every compost event in production. This listener fires
     * AFTER all singletons have initialized and throws loudly if the sink is still
     * null.
     *
     * <p>The runtime null-check inside {@link #applyCompost} is PRESERVED — it covers
     * test profiles (Mockito unit tests, standalone fixtures) that deliberately do
     * not register a sink.
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (compostSink == null) {
            throw new IllegalStateException(
                    "CompostSink was never registered — EnvironmentEngine @PostConstruct ordering regressed. "
                    + "See EnvCleanupHooksBean.onApplicationEvent() — the fail-fast check that caught this.");
        }
        log.debug("EnvCleanupHooksBean: CompostSink registered successfully at context refresh");
    }

    /** Canonical infection map — keyed by Particle id, BondedPair id, or CompositeMember id. */
    final Map<String, Infection> infections = new ConcurrentHashMap<>();

    /** Canonical cure-immunity map — entity id → tick-until-expiry. */
    final Map<String, Long> cureImmuneUntil = new ConcurrentHashMap<>();

    /** Canonical post-damage buff-grant queue. */
    final List<PendingGrant> pendingBuffGrants = Collections.synchronizedList(new ArrayList<>());

    private volatile CompostSink compostSink;

    public void registerCompostSink(CompostSink sink) {
        this.compostSink = sink;
    }

    @Override
    public void clearInfectionOnDeath(String entityId) {
        infections.remove(entityId);
        cureImmuneUntil.remove(entityId);
        removePendingGrantsForEntity(entityId);
    }

    @Override
    public void applyCompost(Position deathPos) {
        CompostSink sink = compostSink;
        if (sink == null) {
            log.debug("applyCompost called before CompostSink registered — skipping (pos={})", deathPos);
            return;
        }
        sink.applyCompost(deathPos);
    }

    /**
     * Plan 14-03 cycle-6 HIGH #2: transfer infection + cureImmuneUntil from
     * {@code fromId} → {@code toId} using MAX semantics for conflicts.
     *
     * <p><b>cycle-9 action B.2 — AUTHORITATIVE OWNERSHIP BOUNDARY:</b>
     * This method migrates ONLY Infection + cureImmuneUntil. It DOES NOT touch
     * buffs. Buff migration is owned exclusively by
     * {@link BuffRegistry#transferBuffs(String, String)}.
     * SimulationEngine identity-transition sites invoke BOTH in sequence:
     * <pre>
     *     hooks.transferMutagenState(fromId, toId);        // THIS method — infection+immunity
     *     buffRegistry.transferBuffs(fromId, toId);         // BuffRegistry — buffs
     * </pre>
     * Cross-check: grep "buff" inside THIS method body returns ZERO matches.
     * If you're tempted to add buff-migration code here, STOP — that violates
     * the ownership boundary and duplicates logic across two components.
     */
    @Override
    public void transferMutagenState(String fromId, String toId) {
        if (fromId == null || toId == null || fromId.equals(toId)) return;
        Infection src = infections.remove(fromId);
        if (src != null) {
            Infection existing = infections.get(toId);
            if (existing == null) {
                infections.put(toId, new Infection(
                        Math.max(src.initialTicks(), src.ticksLeft()),
                        src.strain(),
                        src.damagePerTick(),
                        src.ticksLeft(),
                        src.position()));
            } else {
                int mergedTicksLeft = Math.max(existing.ticksLeft(), src.ticksLeft());
                int mergedInitialTicks = Math.max(existing.initialTicks(), src.initialTicks());
                infections.put(toId, new Infection(
                        mergedInitialTicks,
                        existing.strain(),
                        existing.damagePerTick(),
                        mergedTicksLeft,
                        existing.position()));
            }
        }
        Long cureSrc = cureImmuneUntil.remove(fromId);
        if (cureSrc != null) {
            cureImmuneUntil.merge(toId, cureSrc, Math::max);
        }
    }

    // ── Public accessors (Plan 14-03 Task 2) ──────────────────────

    /** Canonical infection map — package-scoped mutable view. */
    public Map<String, Infection> getInfections() {
        return infections;
    }

    /** Canonical cure-immunity map — entity id → tick-until-expiry. */
    public Map<String, Long> getCureImmuneUntil() {
        return cureImmuneUntil;
    }

    /** Canonical pending-grant queue — returned live so callers can clear/iterate. */
    public List<PendingGrant> getPendingGrants() {
        return pendingBuffGrants;
    }

    /** Append a new pending grant to the queue. */
    public void addPendingGrant(PendingGrant grant) {
        pendingBuffGrants.add(grant);
    }

    /**
     * Remove any pending grants whose {@code entityId} matches. Called from
     * {@link #clearInfectionOnDeath} so a just-dead entity cannot receive a
     * post-mortem buff even if its pending grant was enqueued before death
     * (T-14-03-08 defense-in-depth).
     */
    public void removePendingGrantsForEntity(String entityId) {
        synchronized (pendingBuffGrants) {
            Iterator<PendingGrant> it = pendingBuffGrants.iterator();
            while (it.hasNext()) {
                if (it.next().entityId().equals(entityId)) {
                    it.remove();
                }
            }
        }
    }
}
