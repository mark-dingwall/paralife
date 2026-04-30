package com.paralife.bot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpeciesMix}.
 */
class SpeciesMixTest {

    @Test
    void balancedRoundRobin_isStableAcrossEnumOrder() {
        // Round 2 OpenCode MEDIUM: hardcoded array guarantees stable C,M,S rotation
        // regardless of ParticleType.values() ordering.
        SpeciesMix mix = SpeciesMix.balanced();
        int count = 12;
        List<Character> seq = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            seq.add(mix.pickFor(i, count));
        }
        assertThat(seq).containsExactly(
                'C', 'M', 'S', 'C', 'M', 'S', 'C', 'M', 'S', 'C', 'M', 'S');
    }

    @Test
    void weightedDistribution_4_3_3() {
        // Round 2 Claude LOW: position-based partitioning.
        SpeciesMix mix = new SpeciesMix(0.4, 0.3, 0.3);
        int count = 10;
        int cCount = 0, mCount = 0, sCount = 0;
        for (int i = 0; i < count; i++) {
            char sp = mix.pickFor(i, count);
            if (sp == 'C') cCount++;
            else if (sp == 'M') mCount++;
            else sCount++;
        }
        assertThat(cCount).as("C count").isEqualTo(4);
        assertThat(mCount).as("M count").isEqualTo(3);
        assertThat(sCount).as("S count").isEqualTo(3);
    }

    @Test
    void balanced_sumToOne() {
        // Validation: fractions must sum to 1.0.
        SpeciesMix mix = SpeciesMix.balanced();
        assertThat(mix.cFrac() + mix.mFrac() + mix.sFrac()).isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.001));
    }

    // --- M-04 (Round B): non-finite fractions must be rejected ---

    @Test
    void nanFraction_rejected() {
        assertThatThrownBy(() -> new SpeciesMix(Double.NaN, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveInfinityFraction_rejected() {
        assertThatThrownBy(() -> new SpeciesMix(Double.POSITIVE_INFINITY, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeInfinityFraction_rejected() {
        assertThatThrownBy(() -> new SpeciesMix(Double.NEGATIVE_INFINITY, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- L-02 (Round B): explicit balanced sentinel ---

    @Test
    void manuallyConstructedNearOneThird_doesNotTriggerBalancedBranch() {
        // L-02: a manually constructed mix near (1/3, 1/3, 1/3) must NOT silently use
        // round-robin partitioning. The 4-arg canonical sentinel (false) flows through the
        // 3-arg public constructor, so balanced-mode is reachable only via balanced().
        SpeciesMix mix = new SpeciesMix(0.334, 0.333, 0.333);
        int count = 10;
        List<Character> seq = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            seq.add(mix.pickFor(i, count));
        }
        // Position-based partitioning at count=10 gives a contiguous C-block at the start
        // (frac=0.05/0.15/0.25 < cFrac=0.334). Round-robin would have given M at index 1.
        assertThat(seq.get(0)).as("position-based first slot is C").isEqualTo('C');
        assertThat(seq.get(1)).as("position-based second slot is C, not M (round-robin)").isEqualTo('C');
        assertThat(seq.get(2)).as("position-based third slot is C, not S (round-robin)").isEqualTo('C');
    }
}
