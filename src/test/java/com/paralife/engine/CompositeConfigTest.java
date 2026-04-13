package com.paralife.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeConfigTest {

    // ── defaults() ────────────────────────────────────────────────

    @Test
    void defaultsReturnsValidConfig() {
        var config = CompositeConfig.defaults();
        assertThat(config.canFormComposites()).isTrue();
        assertThat(config.dissolutionChance()).isEqualTo(0.03);
        assertThat(config.criticalEnergyPercent()).isEqualTo(12);
        assertThat(config.speedConstant()).isEqualTo(1.0);
    }

    @Test
    void defaultDrainValuesAreNonNegative() {
        var config = CompositeConfig.defaults();
        assertThat(config.locomotorPassiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.locomotorActiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.feederPassiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.feederActiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.attackerPassiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.attackerActiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.defenderPassiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.reproducerPassiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.reproducerActiveDrain()).isGreaterThanOrEqualTo(0);
        assertThat(config.sensorPassiveDrain()).isGreaterThanOrEqualTo(0);
    }

    // ── Validation ────────────────────────────────────────────────

    @Test
    void dissolutionChanceBelowZeroRejected() {
        assertThatThrownBy(() -> new CompositeConfig(-0.01, 12, 1.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dissolutionChanceAboveOneRejected() {
        assertThatThrownBy(() -> new CompositeConfig(1.01, 12, 1.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void criticalEnergyPercentBelowZeroRejected() {
        assertThatThrownBy(() -> new CompositeConfig(0.03, -1, 1.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void criticalEnergyPercentAbove100Rejected() {
        assertThatThrownBy(() -> new CompositeConfig(0.03, 101, 1.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void speedConstantZeroRejected() {
        assertThatThrownBy(() -> new CompositeConfig(0.03, 12, 0.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void speedConstantNegativeRejected() {
        assertThatThrownBy(() -> new CompositeConfig(0.03, 12, -1.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeDrainValueRejected() {
        // Negative locomotorPassiveDrain
        assertThatThrownBy(() -> new CompositeConfig(0.03, 12, 1.0, -1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Field access ──────────────────────────────────────────────

    @Test
    void explicitValuesAccessible() {
        var config = new CompositeConfig(0.05, 15, 2.0, 2, 4, 2, 3, 2, 5, 2, 2, 3, 2, false);
        assertThat(config.dissolutionChance()).isEqualTo(0.05);
        assertThat(config.criticalEnergyPercent()).isEqualTo(15);
        assertThat(config.speedConstant()).isEqualTo(2.0);
        assertThat(config.locomotorPassiveDrain()).isEqualTo(2);
        assertThat(config.locomotorActiveDrain()).isEqualTo(4);
        assertThat(config.feederPassiveDrain()).isEqualTo(2);
        assertThat(config.feederActiveDrain()).isEqualTo(3);
        assertThat(config.attackerPassiveDrain()).isEqualTo(2);
        assertThat(config.attackerActiveDrain()).isEqualTo(5);
        assertThat(config.defenderPassiveDrain()).isEqualTo(2);
        assertThat(config.reproducerPassiveDrain()).isEqualTo(2);
        assertThat(config.reproducerActiveDrain()).isEqualTo(3);
        assertThat(config.sensorPassiveDrain()).isEqualTo(2);
        assertThat(config.canFormComposites()).isFalse();
    }
}
