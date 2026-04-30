package com.paralife.harness;

import com.paralife.bot.RampUpSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RampUpConverter}'s case/whitespace tolerance (L-03 Round B).
 */
class RampUpConverterTest {

    private final RampUpConverter converter = new RampUpConverter();

    @Test
    void instant_uppercase_parses() {
        assertThat(converter.convert("INSTANT")).isInstanceOf(RampUpSpec.Instant.class);
    }

    @Test
    void instant_mixedCase_parses() {
        assertThat(converter.convert("Instant")).isInstanceOf(RampUpSpec.Instant.class);
    }

    @Test
    void instant_withSurroundingWhitespace_parses() {
        assertThat(converter.convert(" instant ")).isInstanceOf(RampUpSpec.Instant.class);
    }

    @Test
    void rate_uppercase_parses() {
        RampUpSpec spec = converter.convert("RATE:50");
        assertThat(spec).isInstanceOf(RampUpSpec.Rate.class);
        assertThat(((RampUpSpec.Rate) spec).perSecond()).isEqualTo(50);
    }

    @Test
    void rate_mixedCase_parses() {
        RampUpSpec spec = converter.convert("Rate:50");
        assertThat(spec).isInstanceOf(RampUpSpec.Rate.class);
        assertThat(((RampUpSpec.Rate) spec).perSecond()).isEqualTo(50);
    }

    @Test
    void rate_withSurroundingWhitespace_parses() {
        RampUpSpec spec = converter.convert(" rate:50 ");
        assertThat(spec).isInstanceOf(RampUpSpec.Rate.class);
        assertThat(((RampUpSpec.Rate) spec).perSecond()).isEqualTo(50);
    }

    @Test
    void wave_uppercase_parses() {
        RampUpSpec spec = converter.convert("WAVE:100:500");
        assertThat(spec).isInstanceOf(RampUpSpec.Wave.class);
        RampUpSpec.Wave wave = (RampUpSpec.Wave) spec;
        assertThat(wave.count()).isEqualTo(100);
        assertThat(wave.sleepMs()).isEqualTo(500L);
    }
}
