package com.paralife.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickEventTest {

    @Test
    void createsWithTickNumberAndTimestamp() {
        var before = Instant.now();
        var event = new TickEvent(42);
        var after = Instant.now();

        assertThat(event.tickNumber()).isEqualTo(42);
        assertThat(event.timestamp()).isBetween(before, after);
    }

    @Test
    void createsWithExplicitTimestamp() {
        var ts = Instant.parse("2026-01-01T00:00:00Z");
        var event = new TickEvent(1, ts);
        assertThat(event.timestamp()).isEqualTo(ts);
    }
}
