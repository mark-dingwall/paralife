package com.paralife.engine;

import com.paralife.world.Entity.ParticleType;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.stereotype.Component;

/**
 * Cumulative per-species committed-spawn counter (process lifetime).
 *
 * <p>A "spawn" is a committed biological birth/admission — incremented only after
 * successful placement/registration. Admission runs on WebSocket threads and
 * reproduction on the tick thread, so counts are atomic per species (a plain
 * {@code long} map would lose increments). Indexed by {@link ParticleType#ordinal()}.
 *
 * <p>Firewall note: consumers assert only the +1 state-transition delta of a single
 * committed creation — never an accumulated total, share, or {@code > 0} predicate.
 */
@Component
public class SpeciesSpawnCounter {

    private final AtomicLongArray counts = new AtomicLongArray(ParticleType.values().length);

    public void increment(ParticleType type) {
        counts.incrementAndGet(type.ordinal());
    }

    public long get(ParticleType type) {
        return counts.get(type.ordinal());
    }

    /** Immutable point-in-time copy for the observer frame (indexed by ordinal). */
    public long[] snapshot() {
        long[] out = new long[counts.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = counts.get(i);
        }
        return out;
    }
}
