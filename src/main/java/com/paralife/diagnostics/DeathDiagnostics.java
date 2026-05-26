package com.paralife.diagnostics;

import com.paralife.engine.TickEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Death-cause + lifespan diagnostic instrumentation. First resident of
 * {@code com.paralife.diagnostics} — the home for flag-gated runtime
 * instrumentation, distinct from the external profiling scripts/artifacts
 * kept under {@code .planning/.../profiles}.
 *
 * <p>Attributes entity death cause and lifespan to answer: at scale, do entities
 * die from starvation (food deficit), combat (RPS), overcrowding, or environment
 * (toxin/mutagen/lightning)? The production death sweep ({@code
 * SimulationEngine.processDeaths}) only sees {@code !isAlive()} (energy==0) and
 * loses the cause — so each energy-sink site tags a <em>lethal hint</em> here;
 * energy decay is the default (STARVATION) when no other site claimed the kill.
 *
 * <p>Gated by {@code paralife.diagnostics.death-trace.enabled=true}. When the
 * flag is off the bean is absent and every call site's {@code null} guard makes
 * this zero-cost — safe to leave in mainline. Origin: Phase 20 viability
 * investigation (food-deficit death-treadmill, 2026-05-25); retained to support
 * the deferred Population Viability &amp; Energy Balance work.
 *
 * <p>Birth is recorded at the single {@code LiveEntityRegistry.register}
 * chokepoint (covers register + reproduction + budding); death at {@code
 * DeathFinalizer}. Lethal hints are only emitted on the rare tick an entity
 * actually crosses to energy==0, so hot-path overhead is negligible.
 */
@Component
@ConditionalOnProperty(name = "paralife.diagnostics.death-trace.enabled", havingValue = "true")
public class DeathDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(DeathDiagnostics.class);

    public enum Cause { STARVATION, COMBAT, OVERCROWDING, TOXIN, MUTAGEN, LIGHTNING, UNKNOWN }

    private final TickEngine tickEngine;
    private final MeterRegistry meterRegistry;

    private final Map<String, Long> birthTick = new ConcurrentHashMap<>();
    private final Map<String, Cause> lethalHint = new ConcurrentHashMap<>();
    private final Map<String, Integer> preHitEnergy = new ConcurrentHashMap<>();
    private final Map<Cause, LongAdder> causeCounts = new ConcurrentHashMap<>();

    public DeathDiagnostics(TickEngine tickEngine, MeterRegistry meterRegistry) {
        this.tickEngine = tickEngine;
        this.meterRegistry = meterRegistry;
        log.warn("DeathDiagnostics ENABLED — flag-gated death-cause/lifespan trace ACTIVE. "
                + "Disable in production (paralife.diagnostics.death-trace.enabled).");
    }

    /** Birth chokepoint — record spawn tick. Called from LiveEntityRegistry.register. */
    public void recordBirth(String entityId) {
        birthTick.put(entityId, tickEngine.currentTick());
    }

    /**
     * Tag the cause that drove this entity to energy==0. Call AFTER the lethal
     * energy write, only when the entity is now {@code !isAlive()}. First claim
     * wins for the tick (combat before the decay sweep, etc.).
     *
     * @param preHit energy immediately BEFORE the lethal hit (for healthy-kill detection)
     */
    public void hintLethal(String entityId, Cause cause, int preHit) {
        lethalHint.putIfAbsent(entityId, cause);
        preHitEnergy.putIfAbsent(entityId, preHit);
    }

    /**
     * Death finalised — emit the lifecycle record and bump the cause counter.
     * Default cause is STARVATION (no site claimed it → energy decay outran food).
     */
    public void recordDeath(String entityId, String type) {
        Cause cause = lethalHint.getOrDefault(entityId, Cause.STARVATION);
        Long birth = birthTick.remove(entityId);
        Integer preHit = preHitEnergy.remove(entityId);
        lethalHint.remove(entityId);

        long now = tickEngine.currentTick();
        long lifespan = (birth != null) ? (now - birth) : -1L;

        causeCounts.computeIfAbsent(cause, c -> new LongAdder()).increment();
        Counter.builder("paralife.diag.deaths")
                .tag("cause", cause.name().toLowerCase())
                .tag("type", type)
                .register(meterRegistry)
                .increment();

        // One line per death — `grep DEATH-TRACE` to pull the lifecycle sample.
        log.info("DEATH-TRACE id={} type={} cause={} lifespanTicks={} preHitEnergy={} deathTick={}",
                entityId, type, cause, lifespan, preHit, now);
    }

    /** Snapshot of cumulative cause histogram — for the periodic/final summary. */
    public Map<Cause, Long> histogram() {
        Map<Cause, Long> out = new ConcurrentHashMap<>();
        causeCounts.forEach((c, adder) -> out.put(c, adder.sum()));
        return out;
    }
}
