package com.paralife.bot;

import com.paralife.world.Entity.ParticleType;

/**
 * Controls the species distribution across a bot fleet (Phase 18 D-03).
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #balanced()} — round-robin over {@code [CATALYST, MEMBRANE, SPORE]}</li>
 *   <li>{@link #SpeciesMix(double, double, double)} — weighted; deterministic position-based
 *       partitioning so e.g. {@code (0.4, 0.3, 0.3)} with count=10 gives exactly 4C/3M/3S</li>
 * </ul>
 *
 * <p><b>Round 2 OpenCode MEDIUM amendment — hardcoded ordered array.</b>
 * Balanced mode iterates {@link #ORDERED_TYPES} — a fixed three-element array — rather than
 * {@link ParticleType#values()}. This ensures that future enum reordering (e.g. adding a new
 * value between CATALYST and MEMBRANE) cannot silently change the species distribution for
 * existing load tests and benchmarks.
 */
public record SpeciesMix(double cFrac, double mFrac, double sFrac) {

    /**
     * Round 2 OpenCode MEDIUM: hardcoded order so enum reordering of
     * {@link ParticleType#values()} can never silently change species distribution.
     */
    private static final ParticleType[] ORDERED_TYPES =
            { ParticleType.CATALYST, ParticleType.MEMBRANE, ParticleType.SPORE };

    public SpeciesMix {
        double sum = cFrac + mFrac + sFrac;
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalArgumentException(
                    "Species fractions must sum to 1.0 (got " + sum + ")");
        }
        if (cFrac < 0 || mFrac < 0 || sFrac < 0) {
            throw new IllegalArgumentException("All fractions must be >= 0");
        }
    }

    public static SpeciesMix balanced() {
        return new SpeciesMix(1.0 / 3, 1.0 / 3, 1.0 / 3);
    }

    /**
     * Select species for bot at position {@code i} in a fleet of {@code count}.
     *
     * <p>Balanced mode: round-robin over {@link #ORDERED_TYPES} ({@code C, M, S, C, M, S, ...}).
     * Weighted mode: deterministic position-based partitioning — first {@code cFrac × count}
     * slots are CATALYST, next {@code mFrac × count} are MEMBRANE, rest are SPORE.
     *
     * @param i     zero-based bot index
     * @param count total fleet size
     * @return species char: {@code 'C'}, {@code 'M'}, or {@code 'S'}
     */
    public char pickFor(int i, int count) {
        // Balanced: round-robin over the fixed ORDERED_TYPES array.
        if (Math.abs(cFrac - 1.0 / 3) < 0.001 && Math.abs(mFrac - 1.0 / 3) < 0.001) {
            ParticleType t = ORDERED_TYPES[i % ORDERED_TYPES.length];
            return switch (t) {
                case CATALYST -> 'C';
                case MEMBRANE -> 'M';
                case SPORE    -> 'S';
            };
        }
        // Weighted: deterministic position-based partitioning.
        // For (0.4, 0.3, 0.3) and count=10: bots 0..3 → C, 4..6 → M, 7..9 → S.
        double frac = (i + 0.5) / count;
        if (frac < cFrac) return 'C';
        if (frac < cFrac + mFrac) return 'M';
        return 'S';
    }
}
