package com.paralife.engine;

import java.time.Instant;

/**
 * Event published each tick. Carries the tick number and timestamp.
 * Published via Spring's ApplicationEventPublisher.
 */
public record TickEvent(long tickNumber, Instant timestamp) {

    public TickEvent(long tickNumber) {
        this(tickNumber, Instant.now());
    }
}
