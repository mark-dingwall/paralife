package com.paralife.codec;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * One entry in the `s` block per SCHEMA.md §8.1.
 * presence: 1 = entity only, 2 = env only, 3 = both.
 * entityState/envState: base64 bitmask; omitted on wire when 0 (but present=3 entities with 0 entity state carry envState only).
 */
public record CellEntry(Coord coord, int presence, Optional<KindData> kind,
                        OptionalInt entityState, OptionalInt envState) {

    public CellEntry {
        if (presence < 1 || presence > 3) {
            throw new IllegalArgumentException("presence must be 1..3: " + presence);
        }
        if ((presence & 0x01) != 0 && kind.isEmpty()) {
            throw new IllegalArgumentException("presence bit 0 set but kind absent");
        }
        if ((presence & 0x02) != 0 && envState.isEmpty()) {
            throw new IllegalArgumentException("presence bit 1 set but envState absent");
        }
        if (entityState.isPresent() && (entityState.getAsInt() < 0 || entityState.getAsInt() > 63)) {
            throw new IllegalArgumentException("entityState out of range: " + entityState.getAsInt());
        }
        if (envState.isPresent() && (envState.getAsInt() < 0 || envState.getAsInt() > 63)) {
            throw new IllegalArgumentException("envState out of range: " + envState.getAsInt());
        }
    }
}
