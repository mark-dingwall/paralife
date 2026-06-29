package com.paralife.codec;

/** Per SCHEMA.md §8.1. */
public sealed interface KindData permits KindData.Simple, KindData.RockSolo, KindData.RockRun {

    /** Non-rock kind: C/M/S/D/N/T/0-5/F. */
    record Simple(char code) implements KindData {}

    /** Solo rock — 'R' only, no RLE. */
    record RockSolo() implements KindData {}

    /** Rock run: R<dir><count>. additionalCount is 1..63 (total rocks = starter + additionalCount). */
    record RockRun(char direction, int additionalCount) implements KindData {
        public RockRun {
            if (direction < '1' || direction > '9' || direction == '5') {
                throw new IllegalArgumentException("RLE direction must be numpad 1-9 excluding 5: " + direction);
            }
            if (additionalCount < 1 || additionalCount > 63) {
                throw new IllegalArgumentException("RLE additionalCount must be 1..63: " + additionalCount);
            }
        }
    }
}
