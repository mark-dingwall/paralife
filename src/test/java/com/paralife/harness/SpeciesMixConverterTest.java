package com.paralife.harness;

import com.paralife.bot.SpeciesMix;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SpeciesMixConverter}'s case/whitespace tolerance (L-03 Round B).
 *
 * <p>The converter is exercised indirectly via {@link LoadHarnessOptionsTest}, but those
 * tests pin only the canonical lower-case literals. These tests pin the operator-friendly
 * tolerance directly so future refactors of the converter cannot regress it silently.
 */
class SpeciesMixConverterTest {

    private final SpeciesMixConverter converter = new SpeciesMixConverter();

    @Test
    void balanced_uppercase_parses() {
        assertThat(converter.convert("BALANCED")).isEqualTo(SpeciesMix.balanced());
    }

    @Test
    void balanced_mixedCase_parses() {
        assertThat(converter.convert("Balanced")).isEqualTo(SpeciesMix.balanced());
    }

    @Test
    void balanced_withSurroundingWhitespace_parses() {
        assertThat(converter.convert("  balanced  ")).isEqualTo(SpeciesMix.balanced());
    }

    @Test
    void ratio_canonicalLowercase_parses() {
        SpeciesMix mix = converter.convert("0.5:0.3:0.2");
        assertThat(mix.cFrac()).isEqualTo(0.5);
        assertThat(mix.mFrac()).isCloseTo(0.3, org.assertj.core.api.Assertions.within(0.001));
        assertThat(mix.sFrac()).isCloseTo(0.2, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void ratio_withSurroundingWhitespace_parses() {
        SpeciesMix mix = converter.convert("  0.5:0.3:0.2  ");
        assertThat(mix.cFrac()).isEqualTo(0.5);
    }
}
