package com.paralife.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickConfigTest {

    @Test
    void validConfig() {
        var config = new TickConfig(500, true);
        org.assertj.core.api.Assertions.assertThat(config.intervalMs()).isEqualTo(500);
        org.assertj.core.api.Assertions.assertThat(config.autoStart()).isTrue();
    }

    @Test
    void rejectsZeroInterval() {
        assertThatThrownBy(() -> new TickConfig(0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeInterval() {
        assertThatThrownBy(() -> new TickConfig(-100, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
