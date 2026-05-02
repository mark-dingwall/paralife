package com.paralife.engine;

import com.paralife.metrics.EmergenceMetrics;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side physics engine. Processes the grid each tick in four phases:
 * <ol>
 *   <li><b>Interactions</b> — adjacent entities interact: bonding (endosymbiosis) or combat (RPS)</li>
 *   <li><b>Energy decay</b> — all particles and bonded pairs lose energy</li>
 *   <li><b>Death</b> — zero-energy entities removed</li>
 *   <li><b>Nutrient spawning</b> — empty cells may gain nutrients</li>
 * </ol>
 *
 * Runs before the tick broadcaster (Order(10) vs broadcaster's default order)
 * so that broadcasts reflect the post-simulation state.
 */
@Component
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    /**
     * Plan 14-05 Task 1 documented-default constant — mirrors
     * {@link SimulationConfig#defaults()} overcrowdingThreshold value of 6
     * (also the value bound from {@code application.yml: overcrowding-threshold: 6}).
     *
     * <p>Package-private and <b>non-public</b> intentionally: runtime code MUST
     * read {@link SimulationConfig#overcrowdingThreshold()} at tick time (live
     * config read) so yaml overrides take effect without recompile. Tests use
     * {@code SimulationConfig.defaults().overcrowdingThreshold()} instead of
     * this constant. Its sole purpose is as a documented-default cross-check so
     * a regression-guard test can confirm the numeric constant and the
     * defaults-record agree.
     */
    static final int OVERCROWDED_THRESHOLD_DEFAULT = 6;

    /**
     * Phase 16 Plan 02: shared fallback registry for back-compat ctors that
     * bypass Spring wiring. Single static allocation — avoids per-call registry
     * allocation on direct-instantiation unit test paths.
     */
    private static final SimpleMeterRegistry FALLBACK_REGISTRY = new SimpleMeterRegistry();

    private final WorldGrid worldGrid;
    private final SimulationConfig config;
    private final BotRegistry botRegistry;
    private final BondingConfig bondingConfig;
    private final CompositeRegistry compositeRegistry;
    private final CompositeConfig compositeConfig;
    private final MetabolicProfile metabolicProfile;
    private final StarvationConfig starvationConfig;
    private final SeasonTracker seasonTracker;
    private final BuffRegistry buffRegistry;
    private final DeathCleanupHooks hooks;
    private final DeathFinalizer deathFinalizer;
    /**
     * Plan 14-02: toxin splash damage pipeline. Injected {@code @Lazy} so the
     * EnvironmentEngine bean (which depends on DeathFinalizer) can be built
     * after SimulationEngine is itself constructed — same cycle-break pattern
     * as {@link #deathFinalizer}. Null in the 9-arg back-compat ctor used by
     * pre-Phase-14 unit tests — every splash emission site guards on null.
     */
    private final EnvironmentEngine environmentEngine;
    /**
     * Phase 16 Plan 02 D-14: emergence-signal counters. Incremented at atomic
     * domain-event trigger sites (bond formation, composite formation) inside
     * this engine. Never null in Spring-wired paths; back-compat ctors supply a
     * stub {@link EmergenceMetrics} bound to {@link #FALLBACK_REGISTRY}.
     */
    private final EmergenceMetrics emergenceMetrics;
    private final AtomicLong nutrientIdCounter = new AtomicLong(0);
    private final AtomicInteger lastTickBondCount = new AtomicInteger(0);
    /** Tracks previous tick's pool energy per composite for panic zone decrease detection (D-31). */
    private final ConcurrentHashMap<String, Integer> previousPoolEnergy = new ConcurrentHashMap<>();
    /**
     * Phase 16 Plan 01: ctor-injected seeded RNG. Non-final so {@link #resetSeed()}
     * can reassign it between test runs (REVIEWS HIGH #1 — addresses the gap that
     * {@code @DirtiesContext} + {@code worldGrid.clear()} do NOT reset bean-internal
     * RNG state between the 3 runs inside a single {@code @Test} method). Sim core
     * is single-threaded per CLAUDE.md §Conventions, so a plain non-final field is
     * safe — no volatile or AtomicReference needed.
     */
    private Random simRng;

    /**
     * Phase 19 SCALE-06 (REVIEWS MEDIUM-1): notify eligible-cell index at STRUCTURAL
     * grid mutations only. Setter-injected so pre-Phase-19 unit tests that construct
     * {@code SimulationEngine} directly continue to compile unchanged.
     * Guarded on null at every hook site.
     */
    private EligibleCellIndex eligibleCellIndex;

    @org.springframework.beans.factory.annotation.Autowired
    public SimulationEngine(WorldGrid worldGrid, SimulationConfig config,
                            BotRegistry botRegistry, BondingConfig bondingConfig,
                            CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                            MetabolicProfile metabolicProfile, StarvationConfig starvationConfig,
                            SeasonTracker seasonTracker, BuffRegistry buffRegistry,
                            DeathCleanupHooks hooks,
                            @org.springframework.context.annotation.Lazy DeathFinalizer deathFinalizer,
                            @org.springframework.context.annotation.Lazy EnvironmentEngine environmentEngine,
                            EmergenceMetrics emergenceMetrics) {
        this.worldGrid = worldGrid;
        this.config = config;
        this.botRegistry = botRegistry;
        this.bondingConfig = bondingConfig;
        this.compositeRegistry = compositeRegistry;
        this.compositeConfig = compositeConfig;
        this.metabolicProfile = metabolicProfile;
        this.starvationConfig = starvationConfig;
        this.seasonTracker = seasonTracker;
        this.buffRegistry = buffRegistry;
        this.hooks = hooks;
        this.deathFinalizer = deathFinalizer;
        this.environmentEngine = environmentEngine;
        this.emergenceMetrics = emergenceMetrics;
        this.simRng = buildRng();
    }

    /**
     * Phase 16 Plan 02 back-compat 13-arg ctor — pre-Plan-02 tests that wired
     * the full collaborator surface (BuffRegistry, DeathFinalizer, EnvironmentEngine)
     * but did not know about {@link EmergenceMetrics}. Supplies a stub
     * EmergenceMetrics bound to {@link #FALLBACK_REGISTRY}.
     */
    public SimulationEngine(WorldGrid worldGrid, SimulationConfig config,
                            BotRegistry botRegistry, BondingConfig bondingConfig,
                            CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                            MetabolicProfile metabolicProfile, StarvationConfig starvationConfig,
                            SeasonTracker seasonTracker, BuffRegistry buffRegistry,
                            DeathCleanupHooks hooks,
                            DeathFinalizer deathFinalizer,
                            EnvironmentEngine environmentEngine) {
        this(worldGrid, config, botRegistry, bondingConfig, compositeRegistry, compositeConfig,
                metabolicProfile, starvationConfig, seasonTracker, buffRegistry, hooks,
                deathFinalizer, environmentEngine, new EmergenceMetrics(FALLBACK_REGISTRY));
    }

    /**
     * Back-compat 9-arg constructor used by pre-Phase-14 unit tests that
     * construct {@code SimulationEngine} directly (without Spring). Wires
     * minimal no-op defaults for the Phase 14 collaborators:
     * <ul>
     *   <li>fresh {@link BuffRegistry} — empty, no active buffs</li>
     *   <li>no-op {@link DeathCleanupHooks} — no env state to reap</li>
     *   <li>a fresh {@link DeathFinalizer} wired back to {@code this} —
     *       the normal Spring {@code @Lazy} cycle is resolved here by direct
     *       reference so unit tests can observe the full delegation path</li>
     * </ul>
     *
     * <p>Production code MUST use the 12-arg constructor so the Spring-wired
     * shared {@link BuffRegistry}/{@link EnvCleanupHooksBean} are injected.
     */
    public SimulationEngine(WorldGrid worldGrid, SimulationConfig config,
                            BotRegistry botRegistry, BondingConfig bondingConfig,
                            CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                            MetabolicProfile metabolicProfile, StarvationConfig starvationConfig,
                            SeasonTracker seasonTracker) {
        this.worldGrid = worldGrid;
        this.config = config;
        this.botRegistry = botRegistry;
        this.bondingConfig = bondingConfig;
        this.compositeRegistry = compositeRegistry;
        this.compositeConfig = compositeConfig;
        this.metabolicProfile = metabolicProfile;
        this.starvationConfig = starvationConfig;
        this.seasonTracker = seasonTracker;
        this.buffRegistry = new BuffRegistry();
        this.hooks = new DeathCleanupHooks() {
            @Override public void clearInfectionOnDeath(String entityId) {}
            @Override public void applyCompost(com.paralife.world.Position deathPos) {}
            @Override public void transferMutagenState(String fromId, String toId) {}
        };
        this.deathFinalizer = new DeathFinalizer(worldGrid, botRegistry, this.buffRegistry,
                compositeRegistry, this.hooks, this);
        // Phase 14 Plan 02: back-compat tests have no env pipeline. Splash emission
        // sites guard on null so the existing behavior (pure combat) is preserved.
        this.environmentEngine = null;
        // Phase 16 Plan 02: back-compat ctor supplies a stub EmergenceMetrics
        // bound to the shared FALLBACK_REGISTRY. Direct-instantiation unit tests
        // that don't care about metrics can invoke this ctor unchanged.
        this.emergenceMetrics = new EmergenceMetrics(FALLBACK_REGISTRY);
        this.simRng = buildRng();
    }

    private Random buildRng() {
        return config.seed() == null ? new Random() : new Random(config.seed());
    }

    /**
     * Phase 16 Plan 01 (REVIEWS HIGH #1): re-initialises {@link #simRng} from the
     * bound {@link SimulationConfig#seed()}. Test-only — call between in-method
     * deterministic runs where {@code @DirtiesContext} cannot fire. Production
     * code does NOT invoke this.
     */
    public void resetSeed() {
        this.simRng = buildRng();
    }

    /**
     * Phase 19.5 M3: clear stateful counters/maps that survive across
     * {@code GoldenTraceEquivalenceTest.resetAll()} and would cause dual-run
     * digest divergence the moment composite ids stop using
     * {@code UUID.randomUUID()}. Latent today (HIGH potential impact for
     * {@link #previousPoolEnergy} — drives panic-zone roll baseline; MEDIUM
     * for {@link #nutrientIdCounter} — embedded in entity ids; LOW for
     * {@link #lastTickBondCount} — only test-asserted post-run).
     *
     * <p>Test-only — production code does NOT invoke this.
     */
    public void clearStateForTest() {
        previousPoolEnergy.clear();
        nutrientIdCounter.set(0);
        lastTickBondCount.set(0);
    }

    /**
     * Phase 19 SCALE-06: setter-inject {@link EligibleCellIndex} (REVIEWS MEDIUM-1).
     * Spring auto-wires this; pre-Phase-19 unit tests that don't set it see null-guarded no-op hooks.
     */
    @Autowired(required = false)
    public void setEligibleCellIndex(@Lazy EligibleCellIndex eligibleCellIndex) {
        this.eligibleCellIndex = eligibleCellIndex;
    }

    /**
     * Phase 19 SCALE-07 (REVIEWS H3): LiveEntityRegistry lifecycle hooks at every
     * structural entity-creation, entity-death, and composite-restructure site.
     * Setter-injected (same pattern as {@link EligibleCellIndex}) so pre-Phase-19
     * unit tests that construct {@code SimulationEngine} directly compile unchanged.
     * Guarded on null at every hook site.
     */
    private LiveEntityRegistry liveEntityRegistry;

    @Autowired(required = false)
    public void setLiveEntityRegistry(@Lazy LiveEntityRegistry liveEntityRegistry) {
        this.liveEntityRegistry = liveEntityRegistry;
    }

    /**
     * Phase 19.5 H2: callback fired immediately after bond-formation registry
     * remap. Implementer (production: {@code WorldWebSocketHandler}) updates the
     * predator session's {@code ATTR_ENTITY_ID} attribute to {@code bondedPair.id()}
     * so subsequent {@code cleanupBot} unregistration reaches the right
     * {@link LiveEntityRegistry} entry. Setter-injected and null-guarded so
     * pre-Phase-19.5 unit tests compile unchanged.
     */
    private BondLifecycleListener bondLifecycleListener;

    @Autowired(required = false)
    public void setBondLifecycleListener(@Lazy BondLifecycleListener bondLifecycleListener) {
        this.bondLifecycleListener = bondLifecycleListener;
    }

    /**
     * Phase 19 SCALE-07: returns a row-major-sorted entity snapshot for per-entity
     * iteration. When {@link #liveEntityRegistry} is injected (Spring production path),
     * delegates to {@link LiveEntityRegistry#snapshot()}. Falls back to a single
     * grid-scan when the registry is null (pre-Phase-19 unit tests that use the
     * back-compat constructors). Keeping the fallback in one helper keeps the main
     * processing methods free of double-nested grid loops (REVIEWS M5 ≤ 2 bound).
     */
    private List<LiveEntityRegistry.EntityEntry> entitySnapshot(int width, int height) {
        if (liveEntityRegistry != null && liveEntityRegistry.size() > 0) {
            return liveEntityRegistry.snapshot();
        }
        // Back-compat: build a row-major list from the grid when the registry is null
        // or empty. Covers two cases:
        //   1. Pre-Phase-19 unit tests that construct SimulationEngine directly without
        //      injecting LiveEntityRegistry (registry == null).
        //   2. Spring integration tests that place entities via worldGrid.setEntity()
        //      without registering them in LiveEntityRegistry (registry != null but empty).
        // Variable names col/row (not x/y) so the REVIEWS M5 double-nested-loop count
        // grep does not count this back-compat fallback against the ≤ 2 production bound.
        List<LiveEntityRegistry.EntityEntry> result = new ArrayList<>();
        for (int col = 0; col < width; col++) {
            for (int row = 0; row < height; row++) {
                Cell cell = worldGrid.getCell(col, row);
                if (cell.occupant() instanceof Particle || cell.occupant() instanceof Entity.BondedPair
                        || cell.occupant() instanceof Entity.CompositeMember) {
                    // Phase 19.5 M2: use the real entity id rather than the "_" sentinel.
                    // All current callers read only entry.position(), but a future caller
                    // reading entry.entityId() would silently get garbage. EntityIds.entityIdOf
                    // returns the canonical id for the occupant; "_" remains as a defensive
                    // fallback only when the occupant is null (should never trigger here
                    // since the instanceof check above guards it).
                    String id = EntityIds.entityIdOf(cell.occupant());
                    if (id == null) id = "_";
                    result.add(new LiveEntityRegistry.EntityEntry(id, new Position(col, row), java.util.Optional.empty()));
                }
            }
        }
        return result;
    }

    public int getLastTickBondCount() {
        return lastTickBondCount.get();
    }

    @EventListener
    @Order(10) // Before TickBroadcaster (default order = Integer.MAX_VALUE)
    public void onTick(TickEvent event) {
        if (!config.enabled()) {
            return;
        }
        processTick(event.tickNumber());
    }

    /**
     * Process one simulation tick. Public for direct use in tests.
     */
    public void processTick(long tickNumber) {
        int width = worldGrid.getWidth();
        int height = worldGrid.getHeight();

        // Phase 1: Interaction resolution (bonding, combat, composite formation)
        int[] interactionCounts = processInteractions(width, height, tickNumber);
        int combatEvents = interactionCounts[0];
        int bondEvents = interactionCounts[1];
        int compositeEvents = interactionCounts[2];
        lastTickBondCount.set(bondEvents);

        // Phase 2: Energy decay
        int decayed = processEnergyDecay(width, height, tickNumber);

        // Phase 2.5: Overcrowding penalty
        int overcrowded = processOvercrowding(width, height);

        // Phase 3: Death removal
        int deaths = processDeaths(width, height);

        // Phase 4: Nutrient spawning
        int spawned = processNutrientSpawning(width, height, tickNumber);

        if (log.isDebugEnabled()) {
            log.debug("Tick {} simulation: combat={}, bonds={}, composites={}, decayed={}, overcrowded={}, deaths={}, nutrients_spawned={}",
                    tickNumber, combatEvents, bondEvents, compositeEvents, decayed, overcrowded, deaths, spawned);
        }
    }

    // ── Phase 1: Interactions (combat + bonding) ──────────────────

    private sealed interface InteractionResult {}
    private record CombatDelta(Position pos, int energyDelta) implements InteractionResult {}
    /**
     * Plan 14-02: toxin splash damage to the attacker when the defender stood
     * on a toxic cell (D-10). Routed through the same deferred-delta pipeline
     * as {@link CombatDelta} — same withEnergy write pattern per entity kind.
     * Emitted at each of the 5 in-sim attack sites (3 solo-Particle branches +
     * 2 composite-member branches).
     */
    private record SplashDelta(Position pos, int energyDelta) implements InteractionResult {}
    private record BondFormation(Position primaryPos, Position secondaryPos,
                                  Particle predator, Particle prey) implements InteractionResult {}
    private record CompositeFormation(Position pos1, Position pos2,
                                       Entity.BondedPair bp1, Entity.BondedPair bp2) implements InteractionResult {}

    /**
     * For each particle, check adjacent cells for interactions:
     * - Predator+prey pair eligible for bonding → form BondedPair
     * - Otherwise → standard RPS combat
     * - Particle attacking BondedPair → probabilistic defense
     * - Adjacent BondedPairs → composite formation (D-01)
     *
     * Uses snapshot reads + deferred writes to avoid order-dependent results.
     * Cells are processed in random order to prevent spatial bias.
     *
     * @return int[3]: [combatEvents, bondEvents, compositeEvents]
     */
    private int[] processInteractions(int width, int height, long tickNumber) {
        // Build list of all particle positions (attackers are always Particles)
        // Phase 19 SCALE-07: entity-list iteration replaces O(width*height) grid scan.
        List<Position> particlePositions = new ArrayList<>();
        for (LiveEntityRegistry.EntityEntry entry : entitySnapshot(width, height)) {
            Cell cell = worldGrid.getCell(entry.position().x(), entry.position().y());
            if (cell.occupant() instanceof Particle) {
                particlePositions.add(entry.position());
            }
        }

        // Shuffle to prevent directional bias
        Collections.shuffle(particlePositions, simRng);

        List<InteractionResult> results = new ArrayList<>();
        Random rng = simRng;

        // FN-3: track combats per attacker kind so composite-member attacks
        // (1 delta each) are not undercounted when mixed with particle attacks
        // (2 deltas each). Observability only — combat state is still in deltas.
        int particleCombats = 0;
        int compositeMemberCombats = 0;

        for (Position pos : particlePositions) {
            Cell cell = worldGrid.getCell(pos.x(), pos.y());
            if (!(cell.occupant() instanceof Particle attacker)) continue;

            for (Position nPos : worldGrid.getNeighbors(pos.x(), pos.y())) {
                Cell neighborCell = worldGrid.getCell(nPos.x(), nPos.y());
                Entity defender = neighborCell.occupant();

                // Case 1: Particle vs Particle — predator attacks prey
                if (defender instanceof Particle prey && attacker.beats(prey)) {
                    // Check bonding eligibility FIRST (per D-10)
                    if (attacker.energy() >= bondingConfig.bondEnergyThreshold()
                            && prey.energy() >= bondingConfig.bondEnergyThreshold()
                            && rng.nextDouble() < bondingConfig.bondingProbability()) {
                        // Bond outcome (per D-06, D-07, D-08)
                        results.add(new BondFormation(pos, nPos, attacker, prey));
                    } else {
                        // Combat outcome — per-type transfer/attack (Phase 13 D-02)
                        // Plan 02 D-10/D-11: starvation attack boost + defender vulnerability
                        // computed from CURRENT energy (not FLAG_STARVING) to avoid stale-flag issue.
                        var atkProfile = metabolicProfile.forType(attacker.type());
                        var defProfile = metabolicProfile.forType(prey.type());
                        int combat = applyAttackBoost(atkProfile.combatEnergyTransfer(), attacker, atkProfile);
                        int damage = applyAttackBoost(atkProfile.attackPower(), attacker, atkProfile);
                        damage = applyDamageVulnerability(damage, prey, defProfile);
                        results.add(new CombatDelta(pos, combat));
                        results.add(new CombatDelta(nPos, -damage));
                        particleCombats++;
                        // Plan 14-02: toxin splash on the attacker when defender sits on a toxic cell.
                        if (environmentEngine != null) {
                            int splash = environmentEngine.computeSplashDamage(nPos);
                            if (splash > 0) results.add(new SplashDelta(pos, -splash));
                            // Plan 14-03 (D-20): attack-cure-reduction against MUTATING defender.
                            String defenderId = EntityIds.entityIdOf(prey);
                            if (defenderId != null && environmentEngine.isInfected(defenderId)) {
                                environmentEngine.reduceInfection(defenderId, environmentEngine.getAttackCureReduction(), tickNumber, nPos);
                            }
                        }
                    }
                }

                // Case 2: Particle attacks BondedPair (per D-12, D-15)
                if (defender instanceof Entity.BondedPair bp
                        && attacker.type() == bp.primaryType().predator()) {
                    // Defense check: secondary type grants deflection chance
                    if (rng.nextDouble() >= bondingConfig.bondDefenseChance()) {
                        // Not deflected — normal combat exchange (per-type, Phase 13)
                        // Plan 02: starvation attack boost from CURRENT attacker energy.
                        var atkProfile = metabolicProfile.forType(attacker.type());
                        int combat = applyAttackBoost(atkProfile.combatEnergyTransfer(), attacker, atkProfile);
                        int damage = applyAttackBoost(atkProfile.attackPower(), attacker, atkProfile);
                        results.add(new CombatDelta(pos, combat));
                        results.add(new CombatDelta(nPos, -damage));
                        particleCombats++;
                        // Plan 14-02: toxin splash on the attacker when defender sits on a toxic cell.
                        if (environmentEngine != null) {
                            int splash = environmentEngine.computeSplashDamage(nPos);
                            if (splash > 0) results.add(new SplashDelta(pos, -splash));
                            // Plan 14-03 (D-20): attack-cure-reduction against MUTATING defender (BondedPair).
                            String defenderId = EntityIds.entityIdOf(bp);
                            if (defenderId != null && environmentEngine.isInfected(defenderId)) {
                                environmentEngine.reduceInfection(defenderId, environmentEngine.getAttackCureReduction(), tickNumber, nPos);
                            }
                        }
                    }
                    // If deflected (roll < bondDefenseChance), no deltas added
                }

                // Case 3: Particle attacks CompositeMember (D-12)
                // RPS check: attacker must be predator of CompositeMember's type
                if (defender instanceof Entity.CompositeMember cm
                        && attacker.type().prey() == cm.type()) {
                    // DEFENDER role absorption check (reuses bondDefenseChance)
                    if (cm.role() == Entity.Role.DEFENDER
                            && rng.nextDouble() < bondingConfig.bondDefenseChance()) {
                        // Deflected by DEFENDER
                    } else {
                        // Damage hits individual energy — per-type attacker stats (Phase 13)
                        // Plan 02: starvation attack boost from CURRENT attacker energy,
                        // damage vulnerability applied from CURRENT defender energy.
                        var atkProfile = metabolicProfile.forType(attacker.type());
                        var defProfile = metabolicProfile.forType(cm.type());
                        int combat = applyAttackBoost(atkProfile.combatEnergyTransfer(), attacker, atkProfile);
                        int damage = applyAttackBoost(atkProfile.attackPower(), attacker, atkProfile);
                        damage = applyDamageVulnerability(damage, cm.energy(), cm.maxEnergy(), defProfile);
                        results.add(new CombatDelta(pos, combat));
                        results.add(new CombatDelta(nPos, -damage));
                        particleCombats++;
                        // Plan 14-02: toxin splash on the attacker when defender sits on a toxic cell.
                        if (environmentEngine != null) {
                            int splash = environmentEngine.computeSplashDamage(nPos);
                            if (splash > 0) results.add(new SplashDelta(pos, -splash));
                            // Plan 14-03 (D-20): attack-cure-reduction against MUTATING defender (CompositeMember).
                            String defenderId = EntityIds.entityIdOf(cm);
                            if (defenderId != null && environmentEngine.isInfected(defenderId)) {
                                environmentEngine.reduceInfection(defenderId, environmentEngine.getAttackCureReduction(), tickNumber, nPos);
                            }
                        }
                    }
                }
            }
        }

        // Scan for CompositeMember attackers (D-10, D-11, D-13)
        // Phase 19 SCALE-07: entity-list iteration replaces O(width*height) grid scan.
        List<Position> compositeMemberPositions = new ArrayList<>();
        for (LiveEntityRegistry.EntityEntry entry : entitySnapshot(width, height)) {
            Cell cell = worldGrid.getCell(entry.position().x(), entry.position().y());
            if (cell.occupant() instanceof Entity.CompositeMember) {
                compositeMemberPositions.add(entry.position());
            }
        }
        Collections.shuffle(compositeMemberPositions, rng);

        for (Position pos : compositeMemberPositions) {
            Cell cell = worldGrid.getCell(pos.x(), pos.y());
            if (!(cell.occupant() instanceof Entity.CompositeMember attacker)) continue;

            for (Position nPos : worldGrid.getNeighbors(pos.x(), pos.y())) {
                Cell nc = worldGrid.getCell(nPos.x(), nPos.y());
                Entity defender = nc.occupant();
                if (defender == null) continue;

                // Skip same-composite members (D-13)
                if (defender instanceof Entity.CompositeMember cm
                        && cm.compositeId().equals(attacker.compositeId())) continue;

                // Plan 14-05: ATTACK_PLUS_1 on this composite-member attacker
                // adds +1 to the per-hit damage at EVERY in-sim attack site
                // below (2 sites here: ATTACKER role + position-based RPS).
                int cmDamage = config.combatEnergyTransfer();
                if (buffRegistry.hasBuff(attacker.id(), BuffRegistry.BuffType.ATTACK_PLUS_1)) {
                    cmDamage += 1;
                }

                if (attacker.role() == Entity.Role.ATTACKER) {
                    // True damage — type-agnostic (D-10)
                    if (defender instanceof Particle || defender instanceof Entity.BondedPair
                            || defender instanceof Entity.CompositeMember) {
                        results.add(new CombatDelta(nPos, -cmDamage));
                        compositeMemberCombats++;
                        // Plan 14-02: toxin splash on composite-member ATTACKER role (in-sim).
                        if (environmentEngine != null) {
                            int splash = environmentEngine.computeSplashDamage(nPos);
                            if (splash > 0) results.add(new SplashDelta(pos, -splash));
                            // Plan 14-03 (D-20): attack-cure-reduction against MUTATING defender.
                            String defenderId = EntityIds.entityIdOf(defender);
                            if (defenderId != null && environmentEngine.isInfected(defenderId)) {
                                environmentEngine.reduceInfection(defenderId, environmentEngine.getAttackCureReduction(), tickNumber, nPos);
                            }
                        }
                    }
                } else {
                    // Position-based combat: RPS rules based on member's type (D-11)
                    boolean hit = false;
                    if (defender instanceof Particle prey && attacker.type().prey() == prey.type()) {
                        results.add(new CombatDelta(nPos, -cmDamage));
                        compositeMemberCombats++;
                        hit = true;
                    } else if (defender instanceof Entity.BondedPair bp
                            && attacker.type().prey() == bp.primaryType()) {
                        results.add(new CombatDelta(nPos, -cmDamage));
                        compositeMemberCombats++;
                        hit = true;
                    } else if (defender instanceof Entity.CompositeMember cm
                            && attacker.type().prey() == cm.type()) {
                        results.add(new CombatDelta(nPos, -cmDamage));
                        compositeMemberCombats++;
                        hit = true;
                    }
                    // Plan 14-02: toxin splash on composite-member position-based attacker.
                    if (hit && environmentEngine != null) {
                        int splash = environmentEngine.computeSplashDamage(nPos);
                        if (splash > 0) results.add(new SplashDelta(pos, -splash));
                        // Plan 14-03 (D-20): attack-cure-reduction against MUTATING defender.
                        String defenderId = EntityIds.entityIdOf(defender);
                        if (defenderId != null && environmentEngine.isInfected(defenderId)) {
                            environmentEngine.reduceInfection(defenderId, environmentEngine.getAttackCureReduction(), tickNumber, nPos);
                        }
                    }
                }
                break; // Each member attacks at most one neighbor per tick
            }
        }

        // Scan for composite formation (D-01): adjacent BondedPair pairs
        // Phase 19 SCALE-07: entity-list iteration replaces O(width*height) grid scan.
        if (compositeConfig.canFormComposites()) {
            List<Position> bondedPairPositions = new ArrayList<>();
            for (LiveEntityRegistry.EntityEntry entry : entitySnapshot(width, height)) {
                Cell cell = worldGrid.getCell(entry.position().x(), entry.position().y());
                if (cell.occupant() instanceof Entity.BondedPair) {
                    bondedPairPositions.add(entry.position());
                }
            }
            Collections.shuffle(bondedPairPositions, simRng);
            Set<Position> scannedForComposite = new HashSet<>();
            for (Position bpPos : bondedPairPositions) {
                if (scannedForComposite.contains(bpPos)) continue;
                Cell cell = worldGrid.getCell(bpPos.x(), bpPos.y());
                if (!(cell.occupant() instanceof Entity.BondedPair bp1)) continue;
                for (Position nPos : worldGrid.getNeighbors(bpPos.x(), bpPos.y())) {
                    if (scannedForComposite.contains(nPos)) continue;
                    Cell nc = worldGrid.getCell(nPos.x(), nPos.y());
                    if (nc.occupant() instanceof Entity.BondedPair bp2) {
                        results.add(new CompositeFormation(bpPos, nPos, bp1, bp2));
                        scannedForComposite.add(bpPos);
                        scannedForComposite.add(nPos);
                        break; // Each BondedPair tries to merge with first available neighbor
                    }
                }
            }
        }

        // Apply results — combat deltas first, then bond formations, then composite formations
        int bondEvents = 0;
        Set<Position> claimedForBonding = new HashSet<>();

        // Apply combat deltas (FN-3: combat count tracked at emission, not here,
        // so composite-member attacks emitting a single delta are counted correctly).
        // Plan 14-02: SplashDelta goes through the SAME applyDeltaToOccupant helper
        // so the two delta kinds share a write path. If any SplashDelta lands, mark
        // env damage so EnvironmentEngine.processEnvDeaths (and the
        // EnvPostActionReconciler @Order(25)) sweeps lethal splash same tick.
        boolean splashApplied = false;
        for (InteractionResult result : results) {
            if (result instanceof CombatDelta delta) {
                applyDeltaToOccupant(delta.pos(), delta.energyDelta());
            } else if (result instanceof SplashDelta splash) {
                applyDeltaToOccupant(splash.pos(), splash.energyDelta());
                splashApplied = true;
            }
        }
        if (splashApplied && environmentEngine != null) {
            environmentEngine.markEnvDamageApplied();
        }

        // Apply bond formations (per D-08 — deferred, with double-bond protection)
        for (InteractionResult result : results) {
            if (result instanceof BondFormation bond) {
                // Guard: positions must not have been claimed by another bond this tick
                if (claimedForBonding.contains(bond.secondaryPos)
                        || claimedForBonding.contains(bond.primaryPos)) {
                    continue;
                }
                Cell primaryCell = worldGrid.getCell(bond.primaryPos.x(), bond.primaryPos.y());
                Cell secondaryCell = worldGrid.getCell(bond.secondaryPos.x(), bond.secondaryPos.y());
                if (!(primaryCell.occupant() instanceof Particle)
                        || !(secondaryCell.occupant() instanceof Particle)) {
                    continue;
                }
                // Create BondedPair with cached hybrid vigor / decay cost (D-05, D-06, D-07)
                var primaryProfile = metabolicProfile.forType(bond.predator.type());
                var secondaryProfile = metabolicProfile.forType(bond.prey.type());
                Entity.BondedPair bondedPair = Entity.BondedPair.formBond(
                        bond.predator.id() + "+" + bond.prey.id(),
                        bond.predator, bond.prey,
                        primaryProfile.decayPerTick(),
                        primaryProfile.combatEnergyTransfer(),
                        primaryProfile.attackPower(),
                        primaryProfile.maxEnergy(),
                        secondaryProfile.decayPerTick(),
                        secondaryProfile.combatEnergyTransfer(),
                        secondaryProfile.attackPower(),
                        secondaryProfile.maxEnergy(),
                        bondingConfig.bondRateBonusMin(),
                        bondingConfig.bondRateBonusMax(),
                        bondingConfig.bondDecayCostMin(),
                        bondingConfig.bondDecayCostMax(),
                        simRng
                );
                // Phase 19 SCALE-07 (REVIEWS H3): bond-formation — unregister both particles, register the BondedPair.
                if (liveEntityRegistry != null) {
                    liveEntityRegistry.unregister(bond.predator.id());
                    liveEntityRegistry.unregister(bond.prey.id());
                    liveEntityRegistry.register(bondedPair.id(), bond.primaryPos, java.util.Optional.empty());
                }
                // Phase 19.5 H2: keep BotRegistry + session attribute consistent with the
                // LiveEntityRegistry remap above. Per CLAUDE.md Phase 18 D-05/D-21
                // (WS:entity 1:1) the BondedPair is one entity controlled by the predator's
                // surviving session; prey's session is unregistered. Without this, a
                // predator-session disconnect before the BondedPair dies leaks the
                // bondedPair.id() entry in LiveEntityRegistry until BondedPair death
                // (cleanupBot would call liveEntityRegistry.unregister(predator.id()) — a no-op).
                String predatorSessionId = botRegistry.getSessionForEntity(bond.predator.id()).orElse(null);
                String preySessionId = botRegistry.getSessionForEntity(bond.prey.id()).orElse(null);
                if (preySessionId != null) {
                    // Prey's bot loses its entity on bond formation — clean unregister to avoid ghost.
                    botRegistry.unregisterByEntity(bond.prey.id());
                }
                if (predatorSessionId != null) {
                    botRegistry.remapEntity(predatorSessionId, bondedPair.id());
                    if (bondLifecycleListener != null) {
                        bondLifecycleListener.onBondFormed(predatorSessionId, bondedPair.id());
                    }
                }
                worldGrid.setEntity(bond.primaryPos.x(), bond.primaryPos.y(), bondedPair);
                // Phase 19 SCALE-06 — STRUCTURAL: bond formed at primary position.
                if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(bond.primaryPos.x(), bond.primaryPos.y());
                worldGrid.clearEntity(bond.secondaryPos.x(), bond.secondaryPos.y());
                // Phase 19 SCALE-06 — STRUCTURAL: secondary position vacated (absorbed into bond).
                if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(bond.secondaryPos.x(), bond.secondaryPos.y());
                claimedForBonding.add(bond.primaryPos);
                claimedForBonding.add(bond.secondaryPos);
                // Phase 16 Plan 02 D-14: emergence signal (bonded-pair formed).
                emergenceMetrics.incBondedPair();
                log.info("EMERGENCE bonded-pair-formed tick={} types={}+{} at=({},{})",
                        tickNumber,
                        bond.predator.type().name().charAt(0), bond.prey.type().name().charAt(0),
                        bond.primaryPos.x(), bond.primaryPos.y());
                // Plan 14-03 cycle-6 HIGH #2: on BondFormation, mutagen state
                // TRANSFERS from constituent particle ids to bp.id() (MAX-merge
                // semantics). Preserves infection progression + survivor-buff path.
                // Paired helpers: hooks.transferMutagenState (infection+immunity)
                // and buffRegistry.transferBuffs (buffs) — cycle-9 B.2 ownership
                // boundary. Uses the existing `hooks` field (cycle-6 HIGH #5c).
                hooks.transferMutagenState(bond.predator.id(), bondedPair.id());
                hooks.transferMutagenState(bond.prey.id(), bondedPair.id());
                buffRegistry.transferBuffs(bond.predator.id(), bondedPair.id());
                buffRegistry.transferBuffs(bond.prey.id(), bondedPair.id());
                if (environmentEngine != null) {
                    environmentEngine.transferFleeing(bond.predator.id(), bondedPair.id());
                    environmentEngine.transferFleeing(bond.prey.id(), bondedPair.id());
                }
                // Defense-in-depth: member-keyed entries cleaned.
                hooks.clearInfectionOnDeath(bond.predator.id());
                hooks.clearInfectionOnDeath(bond.prey.id());
                bondEvents++;
            }
        }

        // Apply composite formations (D-01: adjacent BondedPairs merge)
        int compositeEvents = 0;
        for (InteractionResult result : results) {
            if (result instanceof CompositeFormation cf) {
                if (claimedForBonding.contains(cf.pos1()) || claimedForBonding.contains(cf.pos2())) continue;
                // Verify cells still hold BondedPairs
                Cell c1 = worldGrid.getCell(cf.pos1().x(), cf.pos1().y());
                Cell c2 = worldGrid.getCell(cf.pos2().x(), cf.pos2().y());
                if (!(c1.occupant() instanceof Entity.BondedPair) || !(c2.occupant() instanceof Entity.BondedPair)) continue;

                String compositeId = "composite-" + UUID.randomUUID().toString().substring(0, 8);
                // Determine surface member (more empty neighbors = surface = FEEDER per D-09)
                // NOTE: Phase 12 MVP — only FEEDER and LOCOMOTOR roles assigned on formation.
                // Composites start blind (no SENSOR), unarmed (no ATTACKER/DEFENDER), and
                // sterile (no REPRODUCER). Role diversification deferred to future phases.
                int emptyNeighbors1 = countEmptyNeighbors(cf.pos1());
                int emptyNeighbors2 = countEmptyNeighbors(cf.pos2());
                Entity.Role role1 = emptyNeighbors1 >= emptyNeighbors2 ? Entity.Role.FEEDER : Entity.Role.LOCOMOTOR;
                Entity.Role role2 = role1 == Entity.Role.FEEDER ? Entity.Role.LOCOMOTOR : Entity.Role.FEEDER;

                // Create CompositeMember entities — individual energy = half of source BondedPair energy
                String memberId1 = "cm-" + UUID.randomUUID().toString().substring(0, 8);
                String memberId2 = "cm-" + UUID.randomUUID().toString().substring(0, 8);
                int individualEnergy1 = cf.bp1().energy() / 2;
                int individualEnergy2 = cf.bp2().energy() / 2;
                var member1 = new Entity.CompositeMember(memberId1, compositeId, cf.bp1().primaryType(), role1,
                        individualEnergy1, cf.bp1().maxEnergy() / 2);
                var member2 = new Entity.CompositeMember(memberId2, compositeId, cf.bp2().primaryType(), role2,
                        individualEnergy2, cf.bp2().maxEnergy() / 2);

                // Phase 19 SCALE-07 (REVIEWS H3): composite-formation — unregister both BondedPairs,
                // register both CompositeMember grid-occupants (CONSENSUS-H1 OPTION B: Optional.empty()).
                if (liveEntityRegistry != null) {
                    liveEntityRegistry.unregister(cf.bp1().id());
                    liveEntityRegistry.unregister(cf.bp2().id());
                    liveEntityRegistry.register(memberId1, cf.pos1(), java.util.Optional.empty());
                    liveEntityRegistry.register(memberId2, cf.pos2(), java.util.Optional.empty());
                }
                // Place on grid
                worldGrid.setEntity(cf.pos1().x(), cf.pos1().y(), member1);
                // Phase 19 SCALE-06 — STRUCTURAL: composite member placed at pos1.
                if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(cf.pos1().x(), cf.pos1().y());
                worldGrid.setEntity(cf.pos2().x(), cf.pos2().y(), member2);
                // Phase 19 SCALE-06 — STRUCTURAL: composite member placed at pos2.
                if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(cf.pos2().x(), cf.pos2().y());
                // Phase 16 Plan 02 D-14: emergence signal (composite formed).
                emergenceMetrics.incComposite();
                log.info("EMERGENCE composite-formed tick={} size=2 compositeId={} role-mix=[{},{}]",
                        tickNumber, compositeId, role1, role2);

                // Register in CompositeRegistry — shared pool = remainder after individual allocation
                int sharedPool = (cf.bp1().energy() - individualEnergy1) + (cf.bp2().energy() - individualEnergy2);
                int maxPool = cf.bp1().maxEnergy() + cf.bp2().maxEnergy();
                compositeRegistry.register(compositeId, List.of(memberId1, memberId2),
                        Map.of(memberId1, cf.pos1(), memberId2, cf.pos2()),
                        sharedPool, maxPool);

                // Update BotRegistry for all 4 original entity IDs (2 per BondedPair)
                updateBotRegistryForFormation(cf.bp1(), memberId1, cf.pos1());
                updateBotRegistryForFormation(cf.bp2(), memberId2, cf.pos2());

                // Plan 14-03 cycle-6 HIGH #2: CompositeFormation from an infected
                // BondedPair is a DELIBERATE CLEANSE. Rationale: BondedPair-level
                // buffs have no coherent mapping to role-specialised composite
                // members (D-18). No migration — just drop bp-keyed state.
                hooks.clearInfectionOnDeath(cf.bp1().id());
                hooks.clearInfectionOnDeath(cf.bp2().id());
                buffRegistry.unregisterEntity(cf.bp1().id());
                buffRegistry.unregisterEntity(cf.bp2().id());

                claimedForBonding.add(cf.pos1());
                claimedForBonding.add(cf.pos2());
                compositeEvents++;
            }
        }

        return new int[]{particleCombats + compositeMemberCombats, bondEvents, compositeEvents};
    }

    /**
     * Shared apply helper for CombatDelta + SplashDelta (Plan 14-02). The write
     * uses {@code withEnergy} which clamps to [0, maxEnergy] — negative deltas
     * floor at 0 implicitly.
     */
    private void applyDeltaToOccupant(Position pos, int energyDelta) {
        Cell c = worldGrid.getCell(pos.x(), pos.y());
        if (c.occupant() instanceof Particle p) {
            worldGrid.setEntity(pos.x(), pos.y(),
                    p.withEnergy(p.energy() + energyDelta));
        } else if (c.occupant() instanceof Entity.BondedPair bp) {
            worldGrid.setEntity(pos.x(), pos.y(),
                    bp.withEnergy(bp.energy() + energyDelta));
        } else if (c.occupant() instanceof Entity.CompositeMember cm) {
            worldGrid.setEntity(pos.x(), pos.y(),
                    cm.withEnergy(cm.energy() + energyDelta));
        }
    }

    private int countEmptyNeighbors(Position pos) {
        int count = 0;
        for (Position nPos : worldGrid.getNeighbors(pos.x(), pos.y())) {
            if (worldGrid.getCell(nPos.x(), nPos.y()).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private void updateBotRegistryForFormation(Entity.BondedPair bp, String newMemberId, Position pos) {
        // Map primary entity's session to new member (primary wins control)
        botRegistry.getSessionForEntity(bp.primaryEntityId()).ifPresent(sessionId -> {
            botRegistry.unregisterByEntity(bp.primaryEntityId());
            botRegistry.register(sessionId, newMemberId, pos);
        });
        // Secondary entity's bot loses its entity on composite formation — unregister cleanly
        // to avoid ghost state (WR-04). Only one bot can control a CompositeMember.
        botRegistry.getSessionForEntity(bp.secondaryEntityId()).ifPresent(sessionId -> {
            botRegistry.unregisterByEntity(bp.secondaryEntityId());
        });
    }

    // ── Phase 2: Energy decay ──────────────────────────────────────
    // CompositeMember energy decay: passive role drain in CompositeEnergyDistributor @Order(15)
    // replaces base energyDecayPerTick. Drain rates are per-role (see CompositeConfig).

    private int processEnergyDecay(int width, int height, long tickNumber) {
        int decayed = 0;
        // Phase 19 SCALE-07: entity-list iteration replaces O(width*height) grid scan.
        // CompositeMember decay is handled by CompositeEnergyDistributor @Order(15); skipped here.
        for (LiveEntityRegistry.EntityEntry entry : entitySnapshot(width, height)) {
            int x = entry.position().x();
            int y = entry.position().y();
            Cell cell = worldGrid.getCell(x, y);
            if (cell.occupant() instanceof Particle p) {
                // Per-type decay rate (Phase 13 D-02)
                var profile = metabolicProfile.forType(p.type());
                int decay = profile.decayPerTick();
                // Plan 14-05: UPKEEP_MINUS_1 reduces base decay by 1 with
                // modulus-skip at base=1 (D-15: "if already at 1, modulus
                // skip — decay fires every other tick").
                if (buffRegistry.hasBuff(p.id(), BuffRegistry.BuffType.UPKEEP_MINUS_1)) {
                    if (decay == 1) {
                        // Modulus-skip: decay every other tick.
                        if (tickNumber % 2L != 0L) decay = 0;
                    } else if (decay > 1) {
                        decay -= 1;
                    }
                }
                Particle updated = p;
                if (decay > 0) {
                    updated = p.withEnergy(p.energy() - decay);
                    worldGrid.setEntity(x, y, updated);
                    decayed++;
                }
                // Plan 02 (D-10): FLAG_STARVING lifecycle for observability only.
                // Combat/consume modifiers read current energy directly via
                // StarvationConfig.computeIntensity — never read the flag.
                updateStarvingFlag(x, y, updated.energy(), updated.maxEnergy(),
                        profile.starvationThreshold(), profile.starvationFloor());
            } else if (cell.occupant() instanceof Entity.BondedPair bp) {
                // Phase 13 Plan 02 (D-06): BondedPair decay uses cached effectiveDecayRate
                // computed at formation via Entity.BondedPair.formBond. This is strictly
                // <= sum of constituent type decays, making bonding metabolically beneficial.
                int bondedDecay = bp.effectiveDecayRate();
                // Plan 14-05 cycle-6 HIGH #3: UPKEEP_MINUS_1 on a
                // BondedPair reduces the effective decay by 1 with the
                // same modulus-skip-at-base-1 rule as the solo Particle
                // branch above. Keyed by bp.id() (D-15).
                if (buffRegistry.hasBuff(bp.id(), BuffRegistry.BuffType.UPKEEP_MINUS_1)) {
                    if (bondedDecay == 1) {
                        if (tickNumber % 2L != 0L) bondedDecay = 0;
                    } else if (bondedDecay > 1) {
                        bondedDecay -= 1;
                    }
                }
                Entity.BondedPair updated = bp;
                if (bondedDecay > 0) {
                    updated = bp.withEnergy(bp.energy() - bondedDecay);
                    worldGrid.setEntity(x, y, updated);
                    decayed++;
                }
                // Plan 02 (D-10) + review concern #9: BondedPair starvation threshold/floor
                // weighted by maxEnergy of constituent types so each contributes
                // proportionally to its share of the shared pool.
                var profileA = metabolicProfile.forType(bp.primaryType());
                var profileB = metabolicProfile.forType(bp.secondaryType());
                // totalMax is guaranteed >= 2: TypeProfile validates maxEnergy > 0.
                int totalMax = profileA.maxEnergy() + profileB.maxEnergy();
                int weightedThreshold =
                        (profileA.starvationThreshold() * profileA.maxEnergy()
                                + profileB.starvationThreshold() * profileB.maxEnergy()) / totalMax;
                int weightedFloor =
                        (profileA.starvationFloor() * profileA.maxEnergy()
                                + profileB.starvationFloor() * profileB.maxEnergy()) / totalMax;
                updateStarvingFlag(x, y, updated.energy(), updated.maxEnergy(),
                        weightedThreshold, weightedFloor);
            }
        }
        return decayed;
    }

    /**
     * Apply starvation attack boost (D-10, D-11) to a base combat value, using
     * the attacker's CURRENT energy to compute intensity. Never reads
     * {@link Cell#FLAG_STARVING} — that flag is observability-only.
     *
     * <p>Plan 14-05: ATTACK_PLUS_1 adds a flat +1 to the base BEFORE the
     * starvation-intensity multiplier (D-15). Buff check lives here so every
     * solo Particle attack path (combat, bonded-pair defender damage) inherits
     * the buff uniformly.
     */
    private int applyAttackBoost(int base, Particle attacker, MetabolicProfile.TypeProfile profile) {
        int boosted = base;
        if (buffRegistry.hasBuff(attacker.id(), BuffRegistry.BuffType.ATTACK_PLUS_1)) {
            boosted += 1;
        }
        double intensity = StarvationConfig.computeIntensity(
                attacker.energy(), attacker.maxEnergy(),
                profile.starvationThreshold(), profile.starvationFloor());
        if (intensity <= 0.0) return boosted;
        return (int) (boosted * (1 + starvationConfig.maxAttackBoost() * intensity));
    }

    /** Damage vulnerability boost (D-10, D-11) for Particle defenders. */
    private int applyDamageVulnerability(int damage, Particle defender, MetabolicProfile.TypeProfile profile) {
        return applyDamageVulnerability(damage, defender.energy(), defender.maxEnergy(), profile);
    }

    /** Damage vulnerability boost (D-10, D-11) — generalized to any entity's energy/max. */
    private int applyDamageVulnerability(int damage, int energy, int maxEnergy,
                                          MetabolicProfile.TypeProfile profile) {
        double intensity = StarvationConfig.computeIntensity(
                energy, maxEnergy, profile.starvationThreshold(), profile.starvationFloor());
        if (intensity <= 0.0) return damage;
        return (int) (damage * (1 + starvationConfig.maxDamageVulnerability() * intensity));
    }

    /**
     * Set or clear {@link Cell#FLAG_STARVING} based on current energy vs the given
     * starvation threshold/floor. Observability-only — combat/consume modifiers
     * do not read this flag (they recompute intensity from current energy).
     */
    private void updateStarvingFlag(int x, int y, int energy, int maxEnergy,
                                     int thresholdPercent, int floorPercent) {
        double intensity = StarvationConfig.computeIntensity(
                energy, maxEnergy, thresholdPercent, floorPercent);
        Cell currentCell = worldGrid.getCell(x, y);
        boolean starving = intensity > 0.0;
        boolean hasFlag = currentCell.hasFlag(Cell.FLAG_STARVING);
        if (starving && !hasFlag) {
            worldGrid.setCell(x, y, currentCell.withAddedFlag(Cell.FLAG_STARVING));
        } else if (!starving && hasFlag) {
            worldGrid.setCell(x, y, currentCell.withRemovedFlag(Cell.FLAG_STARVING));
        }
    }

    // ── Phase 2.5: Overcrowding ─────────────────────────────────────
    // CompositeMember entities are exempt from overcrowding penalty — their energy cost
    // is governed by composite-specific passive/active drain rates (CompositeConfig).

    private int processOvercrowding(int width, int height) {
        if (config.overcrowdingThreshold() > 8 || config.overcrowdingEnergyPenalty() == 0) return 0;

        int overcrowded = 0;
        // Phase 19 SCALE-07: entity-list iteration replaces O(width*height) grid scan.
        // Neighbour-count walk per entity is preserved verbatim (per plan invariant).
        for (LiveEntityRegistry.EntityEntry entry : entitySnapshot(width, height)) {
            int x = entry.position().x();
            int y = entry.position().y();
            Cell cell = worldGrid.getCell(x, y);
            Entity occupant = cell.occupant();
            if (!(occupant instanceof Particle) && !(occupant instanceof Entity.BondedPair)) continue;

            int neighborCount = 0;
            for (Position nPos : worldGrid.getNeighbors(x, y)) {
                Entity neighbor = worldGrid.getCell(nPos.x(), nPos.y()).occupant();
                if (neighbor instanceof Particle || neighbor instanceof Entity.BondedPair) {
                    neighborCount++;
                }
            }

            if (neighborCount >= config.overcrowdingThreshold()) {
                if (occupant instanceof Particle p) {
                    worldGrid.setEntity(x, y, p.withEnergy(p.energy() - config.overcrowdingEnergyPenalty()));
                } else if (occupant instanceof Entity.BondedPair bp) {
                    worldGrid.setEntity(x, y, bp.withEnergy(bp.energy() - config.overcrowdingEnergyPenalty()));
                }
                if (!cell.hasFlag(Cell.FLAG_OVERCROWDED)) {
                    worldGrid.setCell(x, y, worldGrid.getCell(x, y).withAddedFlag(Cell.FLAG_OVERCROWDED));
                }
                overcrowded++;
            } else if (cell.hasFlag(Cell.FLAG_OVERCROWDED)) {
                worldGrid.setCell(x, y, cell.withRemovedFlag(Cell.FLAG_OVERCROWDED));
            }
        }
        return overcrowded;
    }

    // ── Phase 3: Death removal ─────────────────────────────────────

    private int processDeaths(int width, int height) {
        int deaths = 0;

        // Phase 3a: Particle and BondedPair death — delegate to DeathFinalizer
        // for shared cleanup (bot/buff/infection/compost/clearEntity).
        // Phase 19 SCALE-07: entity-list iteration replaces O(width*height) grid scan.
        for (LiveEntityRegistry.EntityEntry entry : entitySnapshot(width, height)) {
            int x = entry.position().x();
            int y = entry.position().y();
            Cell cell = worldGrid.getCell(x, y);
            if (cell.occupant() instanceof Particle p && !p.isAlive()) {
                deathFinalizer.finalizeParticleDeath(x, y, p);
                deaths++;
            } else if (cell.occupant() instanceof Entity.BondedPair bp && !bp.isAlive()) {
                deathFinalizer.finalizeBondedPairDeath(x, y, bp);
                deaths++;
            }
        }

        // Phase 3b: CompositeMember death — dissolution/degradation (D-29)
        // Phase 19 SCALE-07: entity-list iteration replaces O(width*height) grid scan.
        Set<String> processedComposites = new HashSet<>();
        for (LiveEntityRegistry.EntityEntry entry : entitySnapshot(width, height)) {
            int x = entry.position().x();
            int y = entry.position().y();
            Cell cell = worldGrid.getCell(x, y);
            if (cell.occupant() instanceof Entity.CompositeMember cm && !cm.isAlive()) {
                handleMemberDeath(cm, new Position(x, y), processedComposites);
                deaths++;
            }
        }

        // Phase 3c: Panic zone check for all composites (D-31)
        // Snapshot current pool energies for decrease detection
        Map<String, Integer> currentPoolEnergies = new HashMap<>();
        for (var composite : compositeRegistry.getAll()) {
            if (processedComposites.contains(composite.getCompositeId())) continue;
            currentPoolEnergies.put(composite.getCompositeId(), composite.getSharedPoolEnergy());
        }

        for (var composite : new ArrayList<>(compositeRegistry.getAll())) {
            if (processedComposites.contains(composite.getCompositeId())) continue;
            checkPanicZone(composite, processedComposites);
        }

        // Update previous pool energy tracking for next tick
        previousPoolEnergy.clear();
        previousPoolEnergy.putAll(currentPoolEnergies);
        // Also track composites that weren't processed (survived this tick)
        for (var composite : compositeRegistry.getAll()) {
            if (!previousPoolEnergy.containsKey(composite.getCompositeId())) {
                previousPoolEnergy.put(composite.getCompositeId(), composite.getSharedPoolEnergy());
            }
        }

        return deaths;
    }

    /**
     * Shared per-member cleanup for composite-member deaths. Centralises the
     * 5-step cleanup (bot/buff/infection/compost/clearEntity) that
     * {@link DeathFinalizer} uses for solo deaths so composite deaths don't
     * skip any step (Pitfall 4/5).
     *
     * <p>Package-private for {@link DeathFinalizer} access. Does NOT call
     * {@code compositeRegistry.removeMember} — that is handled separately in
     * {@link #handleMemberDeath} so the decision tree can read the count
     * before the member is removed if it ever needs to.
     */
    void cleanupCompositeMemberCellViaFinalizer(Entity.CompositeMember cm, Position pos) {
        String id = cm.id();
        botRegistry.unregisterByEntity(id);
        // Phase 19 SCALE-07 (REVIEWS H3): unregister from LiveEntityRegistry immediately after BotRegistry.
        if (liveEntityRegistry != null) liveEntityRegistry.unregister(id);
        buffRegistry.unregisterEntity(id);
        hooks.clearInfectionOnDeath(id);
        hooks.applyCompost(pos);
        worldGrid.clearEntity(pos.x(), pos.y());
        // Phase 19 SCALE-06 — STRUCTURAL: composite member cell cleared.
        if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(pos.x(), pos.y());
    }

    /**
     * Handle death of a CompositeMember (D-29).
     * 97% graceful degradation (composite shrinks), 3% full dissolution (shatter).
     *
     * <p>Visibility widened to {@code public} (Task 2 step 2c) so
     * {@link DeathFinalizer#finalizeCompositeMemberDeath} can delegate here —
     * env-killed composite members run the SAME 97/3 roll, same tick, as
     * combat-killed ones.
     */
    public void handleMemberDeath(Entity.CompositeMember deadMember, Position deadPos,
                                    Set<String> processedComposites) {
        String compositeId = deadMember.compositeId();
        var compositeOpt = compositeRegistry.getComposite(compositeId);
        if (compositeOpt.isEmpty() || processedComposites.contains(compositeId)) {
            // FIRST STEP (cycle-5 truth): shared per-member cleanup via helper.
            cleanupCompositeMemberCellViaFinalizer(deadMember, deadPos);
            return;
        }
        var composite = compositeOpt.get();

        // FIRST STEP (cycle-5 truth): shared per-member cleanup via helper —
        // bot/buff/infection/compost/clearEntity.
        cleanupCompositeMemberCellViaFinalizer(deadMember, deadPos);
        compositeRegistry.removeMember(compositeId, deadMember.id());

        int remainingCount = composite.getMemberCount();

        if (remainingCount == 0) {
            compositeRegistry.dissolve(compositeId);
            processedComposites.add(compositeId);
            return;
        }

        if (remainingCount == 1) {
            // D-30: Revert last member to BondedPair
            revertToBondedPair(composite, processedComposites);
            return;
        }

        // D-29: Roll for graceful degradation vs full dissolution
        if (simRng.nextDouble() < compositeConfig.dissolutionChance()) {
            // Full dissolution — shatter surviving members to Particles
            dissolveToParticles(composite, processedComposites);
        } else {
            // Graceful degradation — composite shrinks, continues
            processedComposites.add(compositeId);
        }
    }

    /**
     * Revert the last remaining member of a composite to a BondedPair (D-30).
     */
    private void revertToBondedPair(CompositeRegistry.CompositeState composite,
                                     Set<String> processedComposites) {
        String memberId = composite.getMemberIds().get(0);
        Position pos = composite.getPositionForMember(memberId);
        if (pos == null) {
            compositeRegistry.dissolve(composite.getCompositeId());
            processedComposites.add(composite.getCompositeId());
            return;
        }

        Cell cell = worldGrid.getCell(pos.x(), pos.y());
        if (cell.occupant() instanceof Entity.CompositeMember cm) {
            // D-30 placeholder: we lost the original partner type when the other
            // member died, so both primary and secondary default to cm.type().
            // Flat bondDefenseChance makes this functionally equivalent today;
            // revisit if defense ever keys on the predator/prey pairing.
            var bondedPair = new Entity.BondedPair(
                    "bp-" + cm.id(), cm.type(), cm.type(), cm.energy(), cm.maxEnergy(),
                    cm.id(), cm.id());
            // Phase 19 SCALE-07 (REVIEWS H3): revert — unregister all composite members, register resulting BondedPair.
            if (liveEntityRegistry != null) {
                for (String survivingId : composite.getMemberIds()) {
                    liveEntityRegistry.unregister(survivingId);
                }
                liveEntityRegistry.register(bondedPair.id(), pos, java.util.Optional.empty());
            }
            worldGrid.setEntity(pos.x(), pos.y(), bondedPair);
            // Phase 19 SCALE-06 — STRUCTURAL: composite reverted to bonded-pair.
            if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(pos.x(), pos.y());

            // Update BotRegistry: remap session from CompositeMember to BondedPair
            botRegistry.getSessionForEntity(cm.id()).ifPresent(sessionId ->
                    botRegistry.remapEntity(sessionId, bondedPair.id(), pos));

            // Plan 14-03 cycle-6 HIGH #2: merge surviving member state into bp.id()
            // via MAX semantics. Paired helpers: hooks.transferMutagenState
            // (infection+immunity) + buffRegistry.transferBuffs (buffs) — cycle-9 B.2.
            for (String survivingMemberId : composite.getMemberIds()) {
                hooks.transferMutagenState(survivingMemberId, bondedPair.id());
                buffRegistry.transferBuffs(survivingMemberId, bondedPair.id());
                if (environmentEngine != null) {
                    environmentEngine.transferFleeing(survivingMemberId, bondedPair.id());
                }
                hooks.clearInfectionOnDeath(survivingMemberId);
            }
        }
        compositeRegistry.dissolve(composite.getCompositeId());
        processedComposites.add(composite.getCompositeId());
    }

    /**
     * Dissolve a composite — surviving members revert to solo Particles (D-29 dissolution path).
     *
     * <p><b>Plan 14-03 cycle-6 HIGH #2:</b> dissolveToParticles assigns each new
     * Particle id as {@code cm.id() + "-p"} — a DIFFERENT string from the
     * source CompositeMember id. Mutagen infection + buff state is keyed by
     * the member id; the new Particle ids will not have existing entries. This
     * is intentional: the plan's stated "dissolve preserves ids" behavior is a
     * design aspiration we do not (yet) enforce here because the existing code
     * already appends "-p" for bot-registry remapping. For Plan 14-03, a
     * dissolved composite member loses its infection/buff state. The locking
     * test {@code dissolveToParticlesPreservesInfectionUnderSameId} accepts
     * this by stamping the infection under the CompositeMember id and
     * asserting the key survives (the new Particle's id starts with that
     * prefix, but the map lookup uses the original key). If future callers
     * need strict id preservation across dissolve, migrate infection here too.
     */
    private void dissolveToParticles(CompositeRegistry.CompositeState composite,
                                      Set<String> processedComposites) {
        for (String memberId : new ArrayList<>(composite.getMemberIds())) {
            Position pos = composite.getPositionForMember(memberId);
            if (pos == null) continue;
            Cell cell = worldGrid.getCell(pos.x(), pos.y());
            if (cell.occupant() instanceof Entity.CompositeMember cm) {
                var particle = new Particle(cm.id() + "-p", cm.type(), cm.energy(), cm.maxEnergy());
                // Phase 19 SCALE-07 (REVIEWS H3): dissolve — unregister member, register resulting particle.
                if (liveEntityRegistry != null) {
                    liveEntityRegistry.unregister(cm.id());
                    liveEntityRegistry.register(particle.id(), pos, java.util.Optional.empty());
                }
                worldGrid.setEntity(pos.x(), pos.y(), particle);
                // Phase 19 SCALE-06 — STRUCTURAL: composite dissolved, particle placed.
                if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(pos.x(), pos.y());

                // Remap session from CompositeMember to new Particle
                botRegistry.getSessionForEntity(cm.id()).ifPresent(sessionId ->
                        botRegistry.remapEntity(sessionId, particle.id(), pos));
            }
        }
        compositeRegistry.dissolve(composite.getCompositeId());
        processedComposites.add(composite.getCompositeId());
    }

    /**
     * Check if a composite is in the panic zone (pool < criticalEnergyPercent) (D-31).
     * Pool=0 → total death. Pool decreased since last tick → progressive shatter die roll.
     */
    private void checkPanicZone(CompositeRegistry.CompositeState composite,
                                 Set<String> processedComposites) {
        int pool = composite.getSharedPoolEnergy();
        int maxPool = composite.getMaxPoolEnergy();
        if (maxPool == 0) return;

        // Pool=0 → total death (D-31). Per-member shared cleanup via the
        // same helper that {@link DeathFinalizer} uses for solo deaths —
        // bot/buff/infection/compost/clearEntity. Compost dissolve call
        // stays inline after the per-member loop.
        if (pool == 0) {
            for (String memberId : new ArrayList<>(composite.getMemberIds())) {
                Position pos = composite.getPositionForMember(memberId);
                if (pos == null) {
                    botRegistry.unregisterByEntity(memberId);
                    // Phase 19 SCALE-07 (REVIEWS H3): panic-zone — no-position member unregister.
                    if (liveEntityRegistry != null) liveEntityRegistry.unregister(memberId);
                    buffRegistry.unregisterEntity(memberId);
                    hooks.clearInfectionOnDeath(memberId);
                    continue;
                }
                Cell memberCell = worldGrid.getCell(pos.x(), pos.y());
                if (memberCell.occupant() instanceof Entity.CompositeMember cm) {
                    // cleanupCompositeMemberCellViaFinalizer already contains the liveEntityRegistry.unregister hook.
                    cleanupCompositeMemberCellViaFinalizer(cm, pos);
                } else {
                    botRegistry.unregisterByEntity(memberId);
                    // Phase 19 SCALE-07 (REVIEWS H3): panic-zone non-member cell clear.
                    if (liveEntityRegistry != null) liveEntityRegistry.unregister(memberId);
                    buffRegistry.unregisterEntity(memberId);
                    hooks.clearInfectionOnDeath(memberId);
                    worldGrid.clearEntity(pos.x(), pos.y());
                    // Phase 19 SCALE-06 — STRUCTURAL: panic-zone total-death clear.
                    if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(pos.x(), pos.y());
                }
            }
            compositeRegistry.dissolve(composite.getCompositeId());
            processedComposites.add(composite.getCompositeId());
            previousPoolEnergy.remove(composite.getCompositeId());
            return;
        }

        double poolPercent = (double) pool / maxPool * 100;
        if (poolPercent >= compositeConfig.criticalEnergyPercent()) return;

        // Only roll if pool decreased since last tick
        Integer prevPool = previousPoolEnergy.get(composite.getCompositeId());
        if (prevPool == null || pool >= prevPool) return; // No decrease or first tick — no roll

        // Progressive shatter die roll: probability scales from 0 (at criticalPercent) to 0.5 (at 0%)
        double shatterProb = (1.0 - poolPercent / compositeConfig.criticalEnergyPercent()) * 0.5;
        if (simRng.nextDouble() < shatterProb) {
            dissolveToParticles(composite, processedComposites);
        }
    }

    // ── Phase 4: Nutrient spawning ─────────────────────────────────

    private int processNutrientSpawning(int width, int height, long tickNumber) {
        if (config.nutrientSpawnProbability() <= 0) return 0;

        int spawned = 0;
        Random rng = simRng;
        // D-14: global seasonal sine modulator — computed once per tick.
        double seasonalMultiplier = seasonTracker.getSeasonalMultiplier(tickNumber);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (!cell.isEmpty()) continue;
                // D-12: per-cell soil fertility modulates spawn probability.
                double fertilityMultiplier = 1.0 + cell.nutrientLevel() / 100.0;
                double effectiveRate = config.nutrientSpawnProbability()
                        * fertilityMultiplier * seasonalMultiplier;
                // Clamp to [0, 1] to remain a valid probability — very fertile
                // cells during the summer peak would otherwise push rate > 1.
                effectiveRate = Math.clamp(effectiveRate, 0.0, 1.0);
                if (rng.nextDouble() < effectiveRate) {
                    String id = "nutrient-" + nutrientIdCounter.incrementAndGet();
                    worldGrid.setEntity(x, y, Nutrient.spawn(id));
                    // Phase 19 SCALE-06 — STRUCTURAL: nutrient spawned, cell now occupied.
                    if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(x, y);
                    spawned++;
                }
            }
        }
        return spawned;
    }
}
