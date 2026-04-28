package com.paralife.bot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
