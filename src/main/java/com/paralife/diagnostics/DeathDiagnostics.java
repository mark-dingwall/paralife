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
     *
     * <p>{@code lifespanTicks} is the lifetime of this <em>grid id</em>, not a
     * biological lineage: each identity transition (particle→bond→composite→revert
     * →dissolve) re-{@code register}s a fresh id, so a predecessor's lifespan is
     * reaped silently by {@link #forget} and not summed into the successor.
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

    /**
     * Silent reaper for non-death identity transitions and disconnects. Removes
     * this id's lifecycle state from all three maps WITHOUT logging or counting.
     *
     * <p>Mirrors {@link #recordBirth}'s single chokepoint: every {@code
     * LiveEntityRegistry.unregister} reaps here, so the maps cannot grow unbounded
     * when an id leaves the grid by any route other than a finalised death (bond/
     * composite formation source ids, revert/dissolve transitions, disconnect/stall,
     * register-first rollback). On a true death, {@link #recordDeath} runs first
     * (it logs + counts + removes), so this call is a harmless no-op there.
     */
    public void forget(String entityId) {
        birthTick.remove(entityId);
        lethalHint.remove(entityId);
        preHitEnergy.remove(entityId);
    }

    /**
     * Snapshot of cumulative cause histogram. Not currently wired to a caller —
     * an intentional hook for a future periodic/shutdown cause summary (the data
     * also lives in the {@code paralife.diag.deaths} Micrometer counter). See the
     * deferred Population Viability work.
     */
    public Map<Cause, Long> histogram() {
        Map<Cause, Long> out = new ConcurrentHashMap<>();
        causeCounts.forEach((c, adder) -> out.put(c, adder.sum()));
        return out;
    }
}
