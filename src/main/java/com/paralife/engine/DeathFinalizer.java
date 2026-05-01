package com.paralife.engine;

import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Centralises the per-member SHARED-CLEANUP pipeline for every entity death
 * in the simulation (solo, bonded, composite). Created cycle-4 action item #1
 * to break the construction cycle between {@link SimulationEngine} and
 * {@link EnvironmentEngine}.
 *
 * <p>Shared cleanup steps applied in order:
 * <ol>
 *   <li>{@link BotRegistry#unregisterByEntity} — drop bot session binding</li>
 *   <li>{@link BuffRegistry#unregisterEntity} — drop mutagen survivor buffs</li>
 *   <li>{@link DeathCleanupHooks#clearInfectionOnDeath} — drop env-owned infection/buff-grant state</li>
 *   <li>{@link DeathCleanupHooks#applyCompost} — bump Cell.nutrientLevel on death cell + 8 neighbors (D-24/D-25)</li>
 *   <li>{@link WorldGrid#clearEntity} — remove occupant</li>
 * </ol>
 *
 * <p>Composite-member deaths keep their existing same-tick 97/3 dissolution
 * roll in {@link SimulationEngine#handleMemberDeath} — this finalizer
 * delegates back to the sim engine (via {@code @Lazy} to break the one
 * remaining back-edge).
 *
 * <p>{@code finalizeBondedPairDeath} also calls
 * {@code hooks.clearInfectionOnDeath(bp.id())} so BondedPair-keyed infections
 * from Plan 14-03 are reaped when the pair dies (cycle-4 action item #6,
 * Gemini MEDIUM).
 */
@Component
public class DeathFinalizer {

    private static final Logger log = LoggerFactory.getLogger(DeathFinalizer.class);

    private final WorldGrid worldGrid;
    private final BotRegistry botRegistry;
    private final BuffRegistry buffRegistry;
    private final CompositeRegistry compositeRegistry;
    private final DeathCleanupHooks hooks;
    private final SimulationEngine simulationEngine;

    /**
     * Phase 19 SCALE-06 (REVIEWS MEDIUM-1): notify the eligible-cell index after
     * structural grid clears (death removes occupant → cell may become eligible).
     * Setter-injected (same pattern as {@code EnvironmentEngine} in {@code ActionResolver})
     * so pre-Phase-19 unit tests that construct {@code DeathFinalizer} directly compile
     * unchanged; those tests do not exercise the placement path.
     */
    private EligibleCellIndex eligibleCellIndex;

    @Autowired(required = false)
    public void setEligibleCellIndex(@Lazy EligibleCellIndex eligibleCellIndex) {
        this.eligibleCellIndex = eligibleCellIndex;
    }

    /**
     * Plan 14-06 Task 1: monotonic counter of death-finalize events. Increments
     * at the TOP of each finalize* method BEFORE collaborator calls, so the
     * counter reflects "a death was attempted" even if a downstream exception
     * aborts the cleanup pipeline. Used by
     * {@link EnvironmentEngine#getCompostEventCount()} (pass-through) and the
     * phase-gate integration test to assert compost events fired.
     */
    private long deathEventCount = 0L;

    public DeathFinalizer(WorldGrid worldGrid,
                          BotRegistry botRegistry,
                          BuffRegistry buffRegistry,
                          CompositeRegistry compositeRegistry,
                          DeathCleanupHooks hooks,
                          @Lazy SimulationEngine simulationEngine) {
        this.worldGrid = worldGrid;
        this.botRegistry = botRegistry;
        this.buffRegistry = buffRegistry;
        this.compositeRegistry = compositeRegistry;
        this.hooks = hooks;
        this.simulationEngine = simulationEngine;
    }

    /**
     * Finalise death of a solo {@link Particle} — applies shared cleanup and
     * removes the occupant from the grid.
     */
    public void finalizeParticleDeath(int x, int y, Particle p) {
        deathEventCount++;
        String id = p.id();
        botRegistry.unregisterByEntity(id);
        buffRegistry.unregisterEntity(id);
        hooks.clearInfectionOnDeath(id);
        hooks.applyCompost(new Position(x, y));
        worldGrid.clearEntity(x, y);
        // REVIEWS MEDIUM-1 / Phase 19 SCALE-06: STRUCTURAL clear — notify eligible-cell index.
        if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(x, y);
        log.debug("Particle death finalised: id={} pos=({},{})", id, x, y);
    }

    /**
     * Finalise death of a {@link BondedPair} — shared cleanup runs for both
     * member entity ids AND the pair's own id (cycle-4 action item #6), then
     * the occupant is removed.
     */
    public void finalizeBondedPairDeath(int x, int y, BondedPair bp) {
        deathEventCount++;
        String primaryId = bp.primaryEntityId();
        String secondaryId = bp.secondaryEntityId();

        botRegistry.unregisterByEntity(primaryId);
        botRegistry.unregisterByEntity(secondaryId);

        buffRegistry.unregisterEntity(primaryId);
        buffRegistry.unregisterEntity(secondaryId);

        hooks.clearInfectionOnDeath(primaryId);
        hooks.clearInfectionOnDeath(secondaryId);
        hooks.clearInfectionOnDeath(bp.id()); // cycle-4 action item #6 — bp.id() key

        hooks.applyCompost(new Position(x, y));
        worldGrid.clearEntity(x, y);
        // REVIEWS MEDIUM-1 / Phase 19 SCALE-06: STRUCTURAL clear — notify eligible-cell index.
        if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(x, y);
        log.debug("BondedPair death finalised: bp={} primary={} secondary={} pos=({},{})",
                bp.id(), primaryId, secondaryId, x, y);
    }

    /**
     * Convenience overload that creates a fresh {@code processedComposites}
     * set for single-member death callers (env-death sweep). Delegates to the
     * set-accepting overload.
     */
    public void finalizeCompositeMemberDeath(int x, int y, CompositeMember cm) {
        finalizeCompositeMemberDeath(x, y, cm, new HashSet<>());
    }

    /**
     * Finalise death of a {@link CompositeMember} — delegates ENTIRELY to
     * {@link SimulationEngine#handleMemberDeath} so the existing same-tick
     * 97/3 graceful-degradation-vs-shatter roll fires identically whether
     * the death came from combat or environmental damage.
     *
     * <p>The per-member shared cleanup (bot/buff/infection/compost/clearEntity)
     * is performed inside {@code handleMemberDeath} via the helper
     * {@code cleanupCompositeMemberCellViaFinalizer} that SimulationEngine
     * owns (Task 2 Step 2b).
     */
    public void finalizeCompositeMemberDeath(int x, int y, CompositeMember cm,
                                              Set<String> processedComposites) {
        deathEventCount++;
        simulationEngine.handleMemberDeath(cm, new Position(x, y), processedComposites);
    }

    /**
     * Plan 14-06 Task 1: monotonic count of finalize* invocations since bean
     * creation (or last {@link #resetCountForTest}). Used by
     * {@link EnvironmentEngine#getCompostEventCount} as a pass-through — every
     * finalize* call produces exactly one compost event via
     * {@link DeathCleanupHooks#applyCompost}, so the death count IS the compost
     * event count.
     */
    public long getDeathEventCount() {
        return deathEventCount;
    }

    /** Plan 14-06 Task 1: test-only helper to zero the counter between runs. */
    public void resetCountForTest() {
        deathEventCount = 0L;
    }

    // Package-private accessors used by SimulationEngine's helper so the
    // helper can share the same shared-cleanup recipe without duplicating code.

    BotRegistry botRegistry() { return botRegistry; }
    BuffRegistry buffRegistry() { return buffRegistry; }
    DeathCleanupHooks hooks() { return hooks; }
    WorldGrid worldGrid() { return worldGrid; }
    CompositeRegistry compositeRegistry() { return compositeRegistry; }
}
