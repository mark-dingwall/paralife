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
 */
public record MutagenEvent(long spawnTick, Position originCell, int lifetimeTicks) {

    public MutagenEvent {
        if (originCell == null)
            throw new IllegalArgumentException("originCell required");
        if (lifetimeTicks <= 0)
            throw new IllegalArgumentException(
                    "lifetimeTicks must be > 0: " + lifetimeTicks);
    }

    /**
     * True if {@code tick} is at or after the event's spawn tick plus lifetime.
     */
    public boolean isExpired(long tick) {
        return tick >= spawnTick + lifetimeTicks;
    }
}
