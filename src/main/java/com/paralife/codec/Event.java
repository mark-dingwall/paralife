package com.paralife.codec;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One entry in the `v` block per SCHEMA.md §8.4.
 * codes: E/A/H/T/M/R/L/N/S/D/B. Magnitude presence depends on code (see SCHEMA §8.4 table).
 *
 * <p>Phase 19.5 E1: 'B' (absorBed) added for the bond-formation prey-session
 * terminal frame. Mirrors 'D' (Died) — no coord, no magnitude — and the bot
 * client treats both as a respawn trigger (entity gone, fresh session needed).
 * Code letter 'A' was already taken by Attacked; 'B' chosen for "Bonded /
 * absorBed" which is unambiguous against the existing event vocabulary.
 *
 * <p>Phase 19.1 D-15 (B1.2 amendment) — {@link #ALL_CODES} is the single source
 * of truth for event codes. Adding a code here is the contract;
 * {@code PerceptionCodec.validateEventCode} MUST be extended in lockstep (the
 * round-trip test in {@code PerceptionCodecRoundTripTest} enforces this).
 *
 * <p>Shape note: {@code String} (immutable) chosen over {@code char[]} so callers
 * cannot mutate the canonical list. Iterate via {@code ALL_CODES.toCharArray()} or
 * {@code ALL_CODES.chars()}; membership via {@code ALL_CODES.indexOf(code) >= 0}.
 */
public record Event(char code, Optional<Coord> coord, OptionalInt magnitude) {

    /**
     * Phase 19.1 D-15 — single source of truth for all valid event codes.
     * Order matches SCHEMA §8.4 table: magnitude codes first, no-magnitude codes last.
     */
    public static final String ALL_CODES = "EAHTMRLNSDB";

    /**
     * Phase 19.1 follow-up — codes that carry a magnitude byte per SCHEMA §8.4.
     * Subset of {@link #ALL_CODES}. Adding a new MAG-bearing code: append here
     * AND to ALL_CODES; the round-trip test will then exercise the magnitude
     * encode/decode path automatically.
     */
    public static final String MAG_CODES = "EAHTMRL";

    private static final Set<Character> CODE_SET;

    static {
        Set<Character> s = new HashSet<>();
        for (char c : ALL_CODES.toCharArray()) s.add(c);
        CODE_SET = Collections.unmodifiableSet(s);
        // Invariant: every MAG_CODES char must be in ALL_CODES. Fails fast at class load.
        for (char c : MAG_CODES.toCharArray()) {
            if (!CODE_SET.contains(c)) {
                throw new AssertionError("MAG_CODES char '" + c + "' not present in ALL_CODES");
            }
        }
    }

    public Event {
        if (!CODE_SET.contains(code)) {
            throw new IllegalArgumentException("Unknown event code: " + code);
        }
        if (magnitude.isPresent() && (magnitude.getAsInt() < 0 || magnitude.getAsInt() > 63)) {
            throw new IllegalArgumentException("magnitude out of range: " + magnitude.getAsInt());
        }
        boolean codeWantsMagnitude = MAG_CODES.indexOf(code) >= 0;
        if (codeWantsMagnitude != magnitude.isPresent()) {
            throw new IllegalArgumentException(
                "Event code '" + code + "' magnitude shape mismatch: "
                + "code-requires-magnitude=" + codeWantsMagnitude
                + ", magnitude-present=" + magnitude.isPresent());
        }
    }
}
