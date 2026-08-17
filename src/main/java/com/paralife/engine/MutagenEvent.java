package com.paralife.engine;

import com.paralife.world.Position;

/**
 * Immutable record representing an active mutagen outbreak event.
 *
 * <p>Plan 14-03 Task 1: tracks the origin cell where the outbreak was seeded
 * (via Poisson roll at SPRING peak per D-12) and a lifetime window after which
 * gossip propagation stops. After expiry, the mutagen grid enters a zone-decay
 * phase (D-13 cleanup) driven by {@code EnvironmentConfig.Mutagen.zoneDecayTicks}.
 *
 * <p>Max-1 active per type (D-03).
 *
 * @param spawnTick     tick at which this outbreak was spawned
 * @param originCell    cell where the first strain byte was stamped
 * @param lifetimeTicks total ticks the event remains active (driven by
 *                      {@code config.mutagen().outbreakLifetimeTicks()})
 * @param growTicks     ticks the bloom keeps gossiping outward before the front
 *                      freezes (a random draw in the configured min..max range);
 *                      the natural size bound now the radius cap is gone
 */
public record MutagenEvent(long spawnTick, Position originCell, int lifetimeTicks, int growTicks) {

    public MutagenEvent {
        if (originCell == null)
            throw new IllegalArgumentException("originCell required");
        if (lifetimeTicks <= 0)
            throw new IllegalArgumentException(
                    "lifetimeTicks must be > 0: " + lifetimeTicks);
        if (growTicks <= 0)
            throw new IllegalArgumentException(
                    "growTicks must be > 0: " + growTicks);
    }

    /**
     * True if {@code tick} is at or after the event's spawn tick plus lifetime.
     */
    public boolean isExpired(long tick) {
        return tick >= spawnTick + lifetimeTicks;
    }

    /**
     * True while the bloom is still spreading — {@code tick} is within {@code growTicks}
     * of the spawn. Once false, gossip stops and the front is frozen where it stood.
     */
    public boolean isGrowing(long tick) {
        return tick < spawnTick + growTicks;
    }
}
