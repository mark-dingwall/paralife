package com.paralife.codec;

import java.util.Optional;

/**
 * One entry in the `f` block per 15-SCHEMA.md §8.3.
 * codes: I/F/A/M/S/U. ctx carries optional trailing args (FLEEING uses {x,y} abs strike coord).
 */
public record ActiveEffect(char code, long expiryTick, Optional<int[]> ctx) {

    public ActiveEffect {
        if (code != 'I' && code != 'F' && code != 'A' && code != 'M' && code != 'S' && code != 'U') {
            throw new IllegalArgumentException("Unknown effect code: " + code);
        }
        if (expiryTick < 0) throw new IllegalArgumentException("expiryTick negative: " + expiryTick);
    }
}
