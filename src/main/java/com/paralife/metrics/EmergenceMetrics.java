package com.paralife.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Phase 16 D-14 emergence-counter surface. Four Micrometer counters capture the
 * atomic domain-event triggers used as R17 evidence signals — they are
 * incremented at the true formation / grant / infection trigger sites in
 * {@link com.paralife.engine.SimulationEngine} and
 * {@link com.paralife.engine.EnvironmentEngine}, NOT by periodic polling.
 *
 * <p>Names follow Micrometer + Prometheus dot-separated lowercase convention
 * (analog: {@link WebSocketMetrics}).
 *
 * <h2>Counters</h2>
 * <ul>
 *   <li>{@link #M_BONDED_PAIRS} — BondedPair entities formed.</li>
 *   <li>{@link #M_COMPOSITES} — Composite entities formed.</li>
 *   <li>{@link #M_BUFFS_GRANTED} — Survivor buffs newly granted (excludes
 *       {@code BuffRegistry.transferBuffs} and the same-type refresh branch).
 *       This placement addresses REVIEWS HIGH #3 — counting at the registry
 *       level double-counts identity-transfer events as emergence.</li>
 *   <li>{@link #M_INFECTIONS} — Mutagen infections started.</li>
 * </ul>
 */
@Component
public class EmergenceMetrics {

    public static final String M_BONDED_PAIRS  = "paralife.emergence.bonded.pairs.formed";
    public static final String M_COMPOSITES    = "paralife.emergence.composites.formed";
    public static final String M_BUFFS_GRANTED = "paralife.emergence.buffs.granted";
    public static final String M_INFECTIONS    = "paralife.emergence.mutagen.infections";

    private final Counter bondedPairs;
    private final Counter composites;
    private final Counter buffsGranted;
    private final Counter infections;

    public EmergenceMetrics(MeterRegistry registry) {
        this.bondedPairs = Counter.builder(M_BONDED_PAIRS)
                .description("BondedPair entities formed (emergence signal)")
                .register(registry);
        this.composites = Counter.builder(M_COMPOSITES)
                .description("Composite entities formed (emergence signal)")
                .register(registry);
        this.buffsGranted = Counter.builder(M_BUFFS_GRANTED)
                .description("Survivor buffs newly granted (excludes transfer/refresh)")
                .register(registry);
        this.infections = Counter.builder(M_INFECTIONS)
                .description("Mutagen infections started")
                .register(registry);
    }

    public void incBondedPair()  { bondedPairs.increment(); }
    public void incComposite()   { composites.increment(); }
    public void incBuffGranted() { buffsGranted.increment(); }
    public void incInfection()   { infections.increment(); }

    public double bondedPairsFormed() { return bondedPairs.count(); }
    public double compositesFormed()  { return composites.count(); }
    public double buffsGrantedCount() { return buffsGranted.count(); }
    public double infectionsStarted() { return infections.count(); }
}
