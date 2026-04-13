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
    }

    @Test
    void defaultsReturnsExpectedValues() {
        var defaults = BondingConfig.defaults();

        assertThat(defaults.bondEnergyThreshold()).isEqualTo(50);
        assertThat(defaults.bondingProbability()).isEqualTo(0.1);
        assertThat(defaults.bondDefenseChance()).isEqualTo(0.25);
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
