package com.paralife.engine;

import com.paralife.world.Position;

/**
 * Narrow interface implemented by {@link EnvCleanupHooksBean} and consumed by
 * {@link DeathFinalizer}. The compile-time seam for the death-cleanup surface.
 *
 * <p>cycle-4 action item #1: moves the impl to a THIRD bean
 * ({@link EnvCleanupHooksBean}) so neither EnvironmentEngine nor DeathFinalizer
 * depends on the other at construction time.
 *
 * <p>Plan 14-03 Task 2 Step 1 EXTENDS this interface with
 * {@code void transferMutagenState(String fromId, String toId)} (cycle-6 HIGH
 * #5c). Plan 01 ships the two-method version.
 */
public interface DeathCleanupHooks {
    /**
     * Called by DeathFinalizer when an entity dies. Implementations MUST remove
     * any env-owned state keyed by this entity id (infection map, cure-immunity
     * map, pending buff grants).
     */
    void clearInfectionOnDeath(String entityId);

    /**
     * Called by DeathFinalizer when an entity dies. Applies corpse-compost
     * nutrient bumps per D-24/D-25. Delegated to EnvironmentEngine via the
     * {@code CompostSink} collaborator registered post-construction.
     */
    void applyCompost(Position deathPos);

    /**
     * Plan 14-03 cycle-6 HIGH #2 + HIGH #5c: transfer mutagen state
     * (infection + cureImmuneUntil) from {@code fromId} to {@code toId} at
     * identity-transition sites (BondFormation, revertToBondedPair). Merges
     * using MAX semantics where conflicts exist. After transfer, removes
     * {@code fromId} entries from the tracked maps. No-op if {@code fromId}
     * has no state.
     *
     * <p><b>cycle-9 action B.2 — ownership boundary:</b> this method migrates
     * ONLY Infection + cureImmuneUntil. Buff migration is owned by
     * {@link BuffRegistry#transferBuffs(String, String)}; callers invoke both
     * helpers in sequence.
     */
    void transferMutagenState(String fromId, String toId);
}
