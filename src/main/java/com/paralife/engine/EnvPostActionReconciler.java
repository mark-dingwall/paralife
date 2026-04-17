package com.paralife.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * cycle-4 action item #2 (Codex HIGH, 14-02 + 14-03): same-tick reconciler
 * for composite attack-path env side-effects.
 *
 * <p>Runs between {@code ActionResolver(@Order 20)} and
 * {@code PerceptionBroadcaster(@Order 50)}.
 *
 * <p>Re-invokes {@link EnvironmentEngine#processEnvDeaths} so lethal splash
 * damage from composite attacks (added in Plan 14-02) finalises in the same
 * tick it landed. Drains post-action buff grants enqueued by composite attack
 * cures (added in Plan 14-03). Keeps the DI graph acyclic and the tick
 * ordering explicit.
 *
 * <p>NOTE: Plan 14-03 Task 2 Step 4 UPDATES the onTick body to pass
 * {@code event.tickNumber()} to
 * {@link EnvironmentEngine#drainPostActionGrants(long)} (cycle-6 HIGH #5a).
 * Plan 01 ships the no-arg version.
 */
@Component
public class EnvPostActionReconciler {

    private static final Logger log = LoggerFactory.getLogger(EnvPostActionReconciler.class);

    public static final int TICK_ORDER = 25;

    private final EnvironmentEngine environmentEngine;

    public EnvPostActionReconciler(EnvironmentEngine environmentEngine) {
        this.environmentEngine = environmentEngine;
    }

    @EventListener
    @Order(TICK_ORDER)
    public void onTick(TickEvent event) {
        try {
            environmentEngine.processEnvDeaths();
            environmentEngine.drainPostActionGrants();
        } catch (RuntimeException ex) {
            log.error("EnvPostActionReconciler.onTick failed at tick {} — continuing pipeline",
                    event.tickNumber(), ex);
        }
    }
}
