package com.paralife.codec;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * One entry in the `v` block per 15-SCHEMA.md §8.4.
 * codes: E/A/H/T/M/R/L/N/S/D. Magnitude presence depends on code (see SCHEMA §8.4 table).
 */
public record Event(char code, Optional<Coord> coord, OptionalInt magnitude) {

    public Event {
        if (code != 'E' && code != 'A' && code != 'H' && code != 'T'
                && code != 'M' && code != 'R' && code != 'L' && code != 'N'
                && code != 'S' && code != 'D') {
            throw new IllegalArgumentException("Unknown event code: " + code);
        }
        if (magnitude.isPresent() && (magnitude.getAsInt() < 0 || magnitude.getAsInt() > 63)) {
            throw new IllegalArgumentException("magnitude out of range: " + magnitude.getAsInt());
        }
    }
}
