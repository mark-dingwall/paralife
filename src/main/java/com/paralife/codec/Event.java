package com.paralife.codec;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * One entry in the `v` block per 15-SCHEMA.md §8.4.
 * codes: E/A/H/T/M/R/L/N/S/D/B. Magnitude presence depends on code (see SCHEMA §8.4 table).
 *
 * <p>Phase 19.5 E1: 'B' (absorBed) added for the bond-formation prey-session
 * terminal frame. Mirrors 'D' (Died) — no coord, no magnitude — and the bot
 * client treats both as a respawn trigger (entity gone, fresh session needed).
 * Code letter 'A' was already taken by Attacked; 'B' chosen for "Bonded /
 * absorBed" which is unambiguous against the existing event vocabulary.
 */
public record Event(char code, Optional<Coord> coord, OptionalInt magnitude) {

    public Event {
        if (code != 'E' && code != 'A' && code != 'H' && code != 'T'
                && code != 'M' && code != 'R' && code != 'L' && code != 'N'
                && code != 'S' && code != 'D' && code != 'B') {
            throw new IllegalArgumentException("Unknown event code: " + code);
        }
        if (magnitude.isPresent() && (magnitude.getAsInt() < 0 || magnitude.getAsInt() > 63)) {
            throw new IllegalArgumentException("magnitude out of range: " + magnitude.getAsInt());
        }
    }
}
