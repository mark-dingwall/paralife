package com.paralife.engine;

import com.paralife.world.Position;
import java.util.List;
import java.util.Set;

/**
 * Immutable, tick-owned projection of environment field state for the observer
 * visualiser. Values are copied by value (never a reference into the mutable
 * shadow arrays). Toxin carries an intensity magnitude (1–255); mutagen carries a
 * strain identifier (1–255, 0=clean) — NOT a magnitude, so the renderer draws it
 * categorically. Lightning lists coordinates of strikes applied on this tick only.
 * {@code infectedIds} lists entity ids with an active infection at capture time.
 *
 * <p>The compact constructor defensively copies every component, so immutability is a
 * record invariant rather than a convention any one producer must honour.
 */
public record EnvironmentSnapshot(List<EnvCell> toxin, List<EnvCell> mutagen, List<Position> lightning,
                                  Set<String> infectedIds) {

    public EnvironmentSnapshot {
        toxin = List.copyOf(toxin);
        mutagen = List.copyOf(mutagen);
        lightning = List.copyOf(lightning);
        infectedIds = Set.copyOf(infectedIds);
    }

    /** A single non-zero env cell: {@code value} is toxin intensity or mutagen strain id. */
    public record EnvCell(int x, int y, int value) {}
}
