package com.paralife.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BondingConfigTest {

    @Test
    void validConstruction() {
        var config = new BondingConfig(50, 0.1, 0.25);

        assertThat(config.bondEnergyThreshold()).isEqualTo(50);
        assertThat(config.bondingProbability()).isEqualTo(0.1);
        assertThat(config.bondDefenseChance()).isEqualTo(0.25);
        // Phase 13 Plan 02 — 3-arg convenience constructor fills in defaults
        assertThat(config.bondRateBonusMin()).isEqualTo(0.1);
        assertThat(config.bondRateBonusMax()).isEqualTo(0.5);
        assertThat(config.bondDecayCostMin()).isEqualTo(0.6);
        assertThat(config.bondDecayCostMax()).isEqualTo(0.9);
    }

    @Test
    void defaultsReturnsExpectedValues() {
        var defaults = BondingConfig.defaults();

        assertThat(defaults.bondEnergyThreshold()).isEqualTo(50);
        assertThat(defaults.bondingProbability()).isEqualTo(0.1);
        assertThat(defaults.bondDefenseChance()).isEqualTo(0.25);
        // Phase 13 Plan 02 — hybrid vigor and decay cost ranges
        assertThat(defaults.bondRateBonusMin()).isEqualTo(0.1);
        assertThat(defaults.bondRateBonusMax()).isEqualTo(0.5);
        assertThat(defaults.bondDecayCostMin()).isEqualTo(0.6);
        assertThat(defaults.bondDecayCostMax()).isEqualTo(0.9);
    }

    // ── Phase 13 Plan 02: hybrid vigor & bond decay cost range validation ──

    @Test
    void bondRateBonusMinBelowZeroThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 0.1, 0.25, -0.01, 0.5, 0.6, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondRateBonusMin");
    }

    @Test
    void bondRateBonusMinAboveOneThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 0.1, 0.25, 1.1, 1.2, 0.6, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondRateBonusMin");
    }

    @Test
    void bondRateBonusMaxBelowMinThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 0.1, 0.25, 0.5, 0.4, 0.6, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondRateBonusMax");
    }

    @Test
    void bondRateBonusMinEqualsMaxIsAccepted() {
        // Plan 02 explicit: min==max must be valid (edge case for ThreadLocalRandom.nextDouble).
        var config = new BondingConfig(50, 0.1, 0.25, 0.3, 0.3, 0.6, 0.9);
        assertThat(config.bondRateBonusMin()).isEqualTo(0.3);
        assertThat(config.bondRateBonusMax()).isEqualTo(0.3);
    }

    @Test
    void bondDecayCostMinBelowZeroThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 0.1, 0.25, 0.1, 0.5, -0.01, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondDecayCostMin");
    }

    @Test
    void bondDecayCostMaxBelowMinThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 0.1, 0.25, 0.1, 0.5, 0.9, 0.6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondDecayCostMax");
    }

    @Test
    void bondDecayCostMinEqualsMaxIsAccepted() {
        // Plan 02 explicit: min==max must be valid (edge case for ThreadLocalRandom.nextDouble).
        var config = new BondingConfig(50, 0.1, 0.25, 0.1, 0.5, 0.7, 0.7);
        assertThat(config.bondDecayCostMin()).isEqualTo(0.7);
        assertThat(config.bondDecayCostMax()).isEqualTo(0.7);
    }

    @Test
    void bondDecayCostAboveOneThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 0.1, 0.25, 0.1, 0.5, 0.6, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondDecayCostMax");
    }

    @Test
    void negativeThresholdThrows() {
        assertThatThrownBy(() -> new BondingConfig(-1, 0.1, 0.25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondEnergyThreshold");
    }

    @Test
    void bondingProbabilityBelowZeroThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, -0.1, 0.25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondingProbability");
    }

    @Test
    void bondingProbabilityAboveOneThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 1.1, 0.25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondingProbability");
    }

    @Test
    void bondDefenseChanceBelowZeroThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 0.1, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondDefenseChance");
    }

    @Test
    void bondDefenseChanceAboveOneThrows() {
        assertThatThrownBy(() -> new BondingConfig(50, 0.1, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bondDefenseChance");
    }

    @Test
    void edgeValueThresholdZeroAccepted() {
        var config = new BondingConfig(0, 0.1, 0.25);
        assertThat(config.bondEnergyThreshold()).isEqualTo(0);
    }

    @Test
    void edgeValueProbabilityZeroAccepted() {
        var config = new BondingConfig(50, 0.0, 0.25);
        assertThat(config.bondingProbability()).isEqualTo(0.0);
    }

    @Test
    void edgeValueProbabilityOneAccepted() {
        var config = new BondingConfig(50, 1.0, 0.25);
        assertThat(config.bondingProbability()).isEqualTo(1.0);
    }

    @Test
    void edgeValueDefenseZeroAccepted() {
        var config = new BondingConfig(50, 0.1, 0.0);
        assertThat(config.bondDefenseChance()).isEqualTo(0.0);
    }

    @Test
    void edgeValueDefenseOneAccepted() {
        var config = new BondingConfig(50, 0.1, 1.0);
        assertThat(config.bondDefenseChance()).isEqualTo(1.0);
    }
}
