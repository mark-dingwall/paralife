package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ObserverConfigTest {

    @Test
    void defaultsAreDisabledWithPositiveCap() {
        ObserverConfig c = ObserverConfig.defaults();
        assertThat(c.enabled()).as("observer ships disabled by default").isFalse();
        assertThat(c.maxSessions()).as("default cap is positive").isPositive();
    }

    @Test
    void rejectsNonPositiveMaxSessions() {
        assertThatThrownBy(() -> new ObserverConfig(true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-sessions");
    }
}
