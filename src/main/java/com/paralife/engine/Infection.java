package com.paralife.engine;

import com.paralife.world.Position;

/**
 * Immutable per-entity mutagen infection record (Plan 14-03 Task 1).
 *
 * <p>Captures the position of the entity at infection-enqueue time so the
 * Phase-B buff-grant pipeline in {@code EnvironmentEngine.tickBuffsAndInfections}
 * can look up the occupant by position rather than via the infection map
 * (T-14-03-11 — cure-path bug fix).
 *
 * <p>{@code initialTicks} preserves the starting duration so buff duration
 * {@code = initialTicks * buffDurationMultiplier} can be computed at grant time
 * even after {@code ticksLeft} has been decremented to 0 by DoT + attack-cure
 * reduction.
 *
 * @param initialTicks   original infection duration (ticks at enqueue time)
 * @param strain         per-cell strain byte; may mutate by ±1 per gossip hop (D-13)
 * @param damagePerTick  per-tick DoT applied while infected (D-14)
 * @param ticksLeft      remaining duration (decremented by DoT + attack-cure)
 * @param position       entity position captured at infection-enqueue time
 *                       (T-14-03-11 — cure-path bug fix via Position in PendingGrant)
 */
public record Infection(int initialTicks, byte strain, int damagePerTick,
                         int ticksLeft, Position position) {

    public Infection {
        if (initialTicks <= 0)
            throw new IllegalArgumentException("initialTicks must be > 0: " + initialTicks);
        if (damagePerTick < 0)
            throw new IllegalArgumentException("damagePerTick must be >= 0: " + damagePerTick);
        if (ticksLeft < 0)
            throw new IllegalArgumentException("ticksLeft must be >= 0: " + ticksLeft);
        if (position == null)
            throw new IllegalArgumentException("position required");
    }

    /** True if this infection has no remaining ticks. */
    public boolean isExpired() {
        return ticksLeft <= 0;
    }

    /** Return a copy with {@code ticksLeft - 1} (floor at 0). */
    public Infection decrement() {
        return new Infection(initialTicks, strain, damagePerTick,
                Math.max(0, ticksLeft - 1), position);
    }

    /** Return a copy with {@code ticksLeft} reduced by {@code amount} (floor at 0). */
    public Infection reduceBy(int amount) {
        return new Infection(initialTicks, strain, damagePerTick,
                Math.max(0, ticksLeft - amount), position);
    }
}
