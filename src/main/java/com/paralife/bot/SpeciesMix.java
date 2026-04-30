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
 *
 * <p><b>Round B L-02 amendment — explicit balanced sentinel.</b>
 * The 4-arg canonical constructor carries an explicit {@code balanced} flag set only by the
 * {@link #balanced()} factory. {@link #pickFor} branches on that flag rather than on a
 * float-tolerance check against {@code 1.0/3}, so a manually constructed near-balanced mix
 * (e.g. {@code 0.334:0.333:0.333}) reliably uses position-based partitioning.
 */
public record SpeciesMix(double cFrac, double mFrac, double sFrac, boolean isBalanced) {

    /**
     * Round 2 OpenCode MEDIUM: hardcoded order so enum reordering of
     * {@link ParticleType#values()} can never silently change species distribution.
     */
    private static final ParticleType[] ORDERED_TYPES =
            { ParticleType.CATALYST, ParticleType.MEMBRANE, ParticleType.SPORE };

    public SpeciesMix {
        // M-04 (Round B): reject non-finite fractions BEFORE the sum check, because
        // NaN propagates through arithmetic and `NaN > 0.001` is false — the sum check
        // would silently accept (NaN, 0.5, 0.5).
        if (!Double.isFinite(cFrac) || !Double.isFinite(mFrac) || !Double.isFinite(sFrac)) {
            throw new IllegalArgumentException("Species fractions must be finite (got "
                    + cFrac + ", " + mFrac + ", " + sFrac + ")");
        }
        double sum = cFrac + mFrac + sFrac;
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalArgumentException(
                    "Species fractions must sum to 1.0 (got " + sum + ")");
        }
        if (cFrac < 0 || mFrac < 0 || sFrac < 0) {
            throw new IllegalArgumentException("All fractions must be >= 0");
        }
    }

    /**
     * Public 3-arg constructor: weighted mode. Delegates to the canonical 4-arg form with
     * {@code balanced=false} so a manually constructed near-(1/3, 1/3, 1/3) mix never
     * silently flips into round-robin behaviour.
     */
    public SpeciesMix(double cFrac, double mFrac, double sFrac) {
        this(cFrac, mFrac, sFrac, false);
    }

    public static SpeciesMix balanced() {
        return new SpeciesMix(1.0 / 3, 1.0 / 3, 1.0 / 3, true);
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
        // Balanced: round-robin over the fixed ORDERED_TYPES array. L-02 (Round B): branch
        // on the explicit `balanced` sentinel rather than a float-tolerance check.
        if (isBalanced) {
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
