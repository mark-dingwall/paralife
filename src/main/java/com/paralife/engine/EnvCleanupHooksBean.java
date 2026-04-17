package com.paralife.engine;

import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Third-bean Spring component that implements {@link DeathCleanupHooks}
 * (cycle-4 action item #1).
 *
 * <p>Owns the canonical env-state maps that BOTH EnvironmentEngine and
 * DeathFinalizer need to read/write:
 * <ul>
 *   <li>{@code infections} — keyed by entity id (or BondedPair id, per 14-03)</li>
 *   <li>{@code cureImmuneUntil} — post-cure grace-period bookkeeping</li>
 *   <li>{@code pendingBuffGrants} — post-damage-alive-gated buff-grant queue</li>
 * </ul>
 *
 * <p>Infections/maps are populated by EnvironmentEngine in Plan 03. In Plan 01
 * the fields exist (so {@link #clearInfectionOnDeath} is a real no-op on empty
 * maps) but only the test fixtures write to them directly.
 *
 * <p>Compost application is delegated: EnvironmentEngine implements
 * {@link CompostSink} and registers itself on this bean via a
 * {@code @PostConstruct} call. The setter runs AFTER both beans construct, so
 * there is no bean cycle.
 *
 * <p>cycle-9 action A: implements {@link ApplicationListener} on
 * {@link ContextRefreshedEvent} so a missing CompostSink registration is
 * caught loudly at context-refresh time rather than silently no-op'ing
 * compost writes in production.
 */
@Component
public class EnvCleanupHooksBean implements DeathCleanupHooks,
        ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(EnvCleanupHooksBean.class);

    /** Setter-injected collaborator that actually mutates the grid's nutrient levels. */
    public interface CompostSink {
        void applyCompost(Position deathPos);
    }

    /**
     * cycle-9 action A — fail-fast on missing CompostSink.
     *
     * <p>Spring does not guarantee {@code @PostConstruct} ordering between unrelated
     * beans. {@link EnvironmentEngine} registers itself as the sink in its
     * {@code @PostConstruct}; if that callback runs BEFORE the ApplicationContext
     * finishes refreshing and some future refactor drops the ordering, this bean
     * would silently no-op on every compost event in production. This listener fires
     * AFTER all singletons have initialized and throws loudly if the sink is still
     * null.
     *
     * <p>The runtime null-check inside {@link #applyCompost} is PRESERVED — it covers
     * test profiles (Mockito unit tests, standalone fixtures) that deliberately do
     * not register a sink.
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (compostSink == null) {
            throw new IllegalStateException(
                    "CompostSink was never registered — EnvironmentEngine @PostConstruct ordering regressed. "
                    + "See EnvCleanupHooksBean.onApplicationEvent() — the fail-fast check that caught this.");
        }
        log.debug("EnvCleanupHooksBean: CompostSink registered successfully at context refresh");
    }

    /** Canonical infection map — keyed by Particle id, BondedPair id, or CompositeMember id. */
    final Map<String, Object> infections = new ConcurrentHashMap<>();

    /** Canonical cure-immunity map — entity id → tick-until-expiry. */
    final Map<String, Long> cureImmuneUntil = new ConcurrentHashMap<>();

    /** Canonical post-damage buff-grant queue (type Object to avoid coupling with 14-03 PendingGrant record). */
    final List<Object> pendingBuffGrants = Collections.synchronizedList(new ArrayList<>());

    private volatile CompostSink compostSink;

    public void registerCompostSink(CompostSink sink) {
        this.compostSink = sink;
    }

    @Override
    public void clearInfectionOnDeath(String entityId) {
        infections.remove(entityId);
        cureImmuneUntil.remove(entityId);
        // pendingBuffGrants pruned by reference in Plan 03 — Plan 01 has no entries yet.
    }

    @Override
    public void applyCompost(Position deathPos) {
        CompostSink sink = compostSink;
        if (sink == null) {
            log.debug("applyCompost called before CompostSink registered — skipping (pos={})", deathPos);
            return;
        }
        sink.applyCompost(deathPos);
    }
}
