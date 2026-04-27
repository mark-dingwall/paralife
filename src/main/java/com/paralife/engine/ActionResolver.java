package com.paralife.engine;

import com.paralife.admission.AdmissionMetrics;
import com.paralife.codec.Frame;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.Rock;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects bot actions between ticks and resolves them atomically during tick
 * processing.
 *
 * <p>Plan 15-06 Task 2: entire verb-dispatch pipeline rewritten around
 * {@link Frame.ActionFrame}. There is no longer a per-action outbound ack frame
 * sent to the client — the next tick's {@code v} block carries event evidence
 * (SCHEMA §8.4), which supersedes the legacy {@code ActionResult} channel.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Bots submit action frames via {@link #queueAction(String, Frame.ActionFrame)}
 *       (called from the codec-driven {@code WorldWebSocketHandler}).</li>
 *   <li>On {@link TickEvent} {@code @Order(20)}, all queued frames are drained
 *       and dispatched per-verb (SCHEMA §8.6: M/E/A/R/V/L).</li>
 *   <li>Verb {@code V} aggregates ranked preferences per composite into an IRV
 *       ballot set; the winning direction drives rigid-body movement.</li>
 *   <li>Verb {@code L} enqueues an alarm on {@link AlarmQueue} — drained by the
 *       {@code TickBroadcaster} (plan 15-08) when building the LOCOMOTOR's next
 *       {@code v} block.</li>
 * </ol>
 *
 * <p>Conflict resolution: when multiple entities target the same cell, the
 * queue is shuffled and the first entity wins; others fall back silently (no
 * ack).
 */
@Component
public class ActionResolver {

    private static final Logger log = LoggerFactory.getLogger(ActionResolver.class);

    /**
     * Legacy flat reproduce cost shared by {@code ActionResolverTest} and
     * {@code CompositeActionTest} as a canonical fixture value — those tests
     * build a {@link MetabolicProfile.TypeProfile} with this cost so their
     * per-type assertions line up with the pre-Phase-13 expected energy math.
     * Production code never reads this; it uses
     * {@link MetabolicProfile.TypeProfile#reproduceEnergyCost()}.
     */
    public static final int REPRODUCE_ENERGY_COST = 30;
    /**
     * Legacy flat child start energy — paired with {@link #REPRODUCE_ENERGY_COST}
     * as a shared test fixture. Production code uses
     * {@link MetabolicProfile.TypeProfile#childStartEnergy()} (= maxEnergy/2).
     */
    public static final int CHILD_START_ENERGY = 20;

    private final WorldGrid worldGrid;
    private final BotRegistry botRegistry;
    private final SessionRegistry sessionRegistry;
    private final SimulationConfig config;
    private final CompositeRegistry compositeRegistry;
    private final CompositeConfig compositeConfig;
    private final MetabolicProfile metabolicProfile;
    private final StarvationConfig starvationConfig;
    private final AlarmQueue alarmQueue;
    private final AtomicLong childIdCounter = new AtomicLong(0);

    /** Tracks ticks since last movement per composite, for speed gating (D-23). */
    private final ConcurrentHashMap<String, Integer> compositeTicksSinceMove = new ConcurrentHashMap<>();

    /**
     * Tracks last-reproduced tick per entity id for per-type reproduce cooldown
     * enforcement (Phase 13 D-02). Pruned each tick alongside
     * {@link #compositeTicksSinceMove} to avoid unbounded growth.
     */
    private final ConcurrentHashMap<String, Long> lastReproducedTick = new ConcurrentHashMap<>();

    /**
     * Plan 14-02: toxin splash damage on composite-member ATTACKER role going
     * through this resolver's direct-write attack path. Setter-injected via
     * {@link #setEnvironmentEngine} so existing unit-test constructors (pre-Phase-14)
     * continue to compile without bumping every test fixture. Guarded on null
     * so tests that don't set it see the pure combat behavior.
     */
    private EnvironmentEngine environmentEngine;

    /**
     * Plan 14-05: BuffRegistry read surface for buff effect application.
     * Setter-injected so pre-Phase-14 unit-test constructors continue to
     * compile without bumping every test fixture. An empty registry is wired
     * in the setter when Spring doesn't provide one (null-safe default).
     */
    private BuffRegistry buffRegistry = new BuffRegistry();

    /**
     * Phase 17 Plan 10 (D-09): aggregate ingress-overwrite counter. Incremented
     * once for every {@link #pendingActions} {@code put} that returned a non-null
     * previous value (i.e. last-write-wins collapsed an unprocessed action frame
     * for the same session within the same tick). Setter-injected ({@code required=false})
     * so pre-Phase-17 unit-test constructors continue to compile without wiring
     * a {@link AdmissionMetrics} bean.
     *
     * <p>Purely observational — no auto-disconnect, no rate-limit feedback. The
     * collapse itself is the protective behavior (D-09); this counter just makes
     * the rate visible to operators via {@code paralife.admission.ingress.overwrites}.
     */
    private AdmissionMetrics admissionMetrics;

    /** Current tick number — captured on the {@link TickEvent} for verb-L alarm routing. */
    private volatile long currentTick = 0L;

    /**
     * Phase 16 Plan 01: ctor-injected seeded RNG for Collections.shuffle tie-break
     * and bonus-offspring die rolls. Non-final so {@link #resetSeed()} can reassign
     * it between test runs (REVIEWS HIGH #1). Bound from
     * {@link SimulationConfig#actionSeed()} — null = unseeded (production).
     */
    private Random actionRng;

    /**
     * Pending action: sessionId → {@link Frame.ActionFrame}. Only the last
     * frame per session per tick is kept. Uses {@link AtomicReference} swap
     * for atomic drain — see {@link #onTick}.
     */
    private final AtomicReference<ConcurrentHashMap<String, Frame.ActionFrame>> pendingActions =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /**
     * Pending ranked preferences from LOCOMOTOR composite members (verb V).
     * Keyed by sessionId, drained alongside {@link #pendingActions} on tick.
     * Each entry is the raw 3-char numpad string carried in
     * {@code ActionFrame.arg()}.
     */
    private final AtomicReference<ConcurrentHashMap<String, String>> pendingVoteBallots =
            new AtomicReference<>(new ConcurrentHashMap<>());

    @Autowired
    public ActionResolver(WorldGrid worldGrid, BotRegistry botRegistry,
                           SessionRegistry sessionRegistry, SimulationConfig config,
                           CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                           MetabolicProfile metabolicProfile,
                           StarvationConfig starvationConfig,
                           AlarmQueue alarmQueue) {
        this.worldGrid = worldGrid;
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.config = config;
        this.compositeRegistry = compositeRegistry;
        this.compositeConfig = compositeConfig;
        this.metabolicProfile = metabolicProfile;
        this.starvationConfig = starvationConfig;
        this.alarmQueue = alarmQueue;
        this.actionRng = buildRng();
    }

    private Random buildRng() {
        return config.actionSeed() == null ? new Random() : new Random(config.actionSeed());
    }

    /**
     * Phase 16 Plan 01 (REVIEWS HIGH #1): re-initialises {@link #actionRng} from
     * {@link SimulationConfig#actionSeed()}. Test-only.
     */
    public void resetSeed() {
        this.actionRng = buildRng();
    }

    /**
     * Convenience constructor for tests predating Plan 15-06 that don't need
     * an AlarmQueue wired in (falls back to a fresh local instance — verb-L
     * dispatch still works, the queue is just not shared with the broadcaster).
     */
    public ActionResolver(WorldGrid worldGrid, BotRegistry botRegistry,
                           SessionRegistry sessionRegistry, SimulationConfig config,
                           CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                           MetabolicProfile metabolicProfile,
                           StarvationConfig starvationConfig) {
        this(worldGrid, botRegistry, sessionRegistry, config, compositeRegistry, compositeConfig,
                metabolicProfile, starvationConfig, new AlarmQueue());
    }

    /**
     * Convenience constructor for tests predating Phase 13 Plan 02 that don't
     * need starvation modifiers (falls back to {@link StarvationConfig#defaults()}).
     */
    public ActionResolver(WorldGrid worldGrid, BotRegistry botRegistry,
                           SessionRegistry sessionRegistry, SimulationConfig config,
                           CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                           MetabolicProfile metabolicProfile) {
        this(worldGrid, botRegistry, sessionRegistry, config,
                compositeRegistry, compositeConfig, metabolicProfile, StarvationConfig.defaults(),
                new AlarmQueue());
    }

    /**
     * Setter-inject {@link EnvironmentEngine} after construction (Plan 14-02).
     * Spring wires this automatically via {@link Autowired}; {@code required=false}
     * keeps pre-Phase-14 unit tests that never set this field working as-is.
     */
    @Autowired(required = false)
    public void setEnvironmentEngine(@org.springframework.context.annotation.Lazy EnvironmentEngine environmentEngine) {
        this.environmentEngine = environmentEngine;
    }

    /**
     * Plan 14-05: setter-inject {@link BuffRegistry} after construction.
     */
    @Autowired(required = false)
    public void setBuffRegistry(BuffRegistry buffRegistry) {
        if (buffRegistry != null) this.buffRegistry = buffRegistry;
    }

    /**
     * Phase 17 Plan 10 (D-09): setter-inject {@link AdmissionMetrics}. Pre-Phase-17
     * unit-test constructors don't wire a metrics bean — when null the overwrite
     * counter is a silent no-op (the last-write-wins collapse still happens; only
     * the operator-visibility counter is suppressed).
     */
    @Autowired(required = false)
    public void setAdmissionMetrics(AdmissionMetrics admissionMetrics) {
        this.admissionMetrics = admissionMetrics;
    }

    /** Package-private read accessor for tests. */
    BuffRegistry getBuffRegistry() {
        return buffRegistry;
    }

    /**
     * Plan 14-05: walk {@code range} steps in {@code dir} from {@code from},
     * falling back one step at a time if the far cell is blocked. FN-9
     * fallback preserved.
     */
    Position findTargetAtRange(Position from, Direction dir, int range,
                                Set<Position> claimedCells, int w, int h) {
        if (range < 1) return null;
        int minCandidate = range > 1 ? range - 1 : 1;
        for (int candidate = range; candidate >= minCandidate; candidate--) {
            Position t = from;
            for (int step = 0; step < candidate; step++) {
                t = dir.apply(t, w, h);
            }
            if (claimedCells.contains(t)) continue;
            if (worldGrid.getCell(t.x(), t.y()).hasOccupant()) continue;
            return t;
        }
        return null;
    }

    /** MOVEMENT_PLUS_1 buff check — LOCOMOTOR members only. */
    boolean hasAnyLocomotorMovementBuff(CompositeRegistry.CompositeState composite) {
        return composite.getMemberIds().stream()
                .filter(id -> isLocomotor(id, composite))
                .anyMatch(id -> buffRegistry.hasBuff(id, BuffRegistry.BuffType.MOVEMENT_PLUS_1));
    }

    private boolean isLocomotor(String memberId, CompositeRegistry.CompositeState composite) {
        Position pos = composite.getPositionForMember(memberId);
        if (pos == null) return false;
        Cell cell = worldGrid.getCell(pos.x(), pos.y());
        return cell.occupant() instanceof Entity.CompositeMember cm
                && cm.role() == Entity.Role.LOCOMOTOR;
    }

    // ── Queue ─────────────────────────────────────────────────────

    /**
     * Queue an action frame from a bot. Replaces any previous action for the
     * same session this tick. Verb {@code V} also captures the 3-char numpad
     * ballot into {@link #pendingVoteBallots} for LOCOMOTOR IRV resolution;
     * verb {@code L} routes immediately to {@link AlarmQueue}.
     */
    public void queueAction(String sessionId, Frame.ActionFrame action) {
        if (action == null) return;
        Frame.ActionFrame previous = pendingActions.get().put(sessionId, action);
        if (previous != null && admissionMetrics != null) {
            // D-09: last-write-wins collapse — increment the aggregate ingress-overwrite
            // counter (paralife.admission.ingress.overwrites). Observational only; the
            // collapse itself is the protective behavior. No auto-disconnect.
            admissionMetrics.incIngressOverwrite();
        }
        if (action.verb() == 'V' && action.arg().isPresent()) {
            pendingVoteBallots.get().put(sessionId, action.arg().get());
        }
        if (action.verb() == 'L') {
            // Verb L dispatches immediately — alarms are point-in-time and must be
            // visible on the next tick's LOCOMOTOR frame even if the member's
            // action is later overridden by a subsequent frame (unlikely but
            // semantically cleaner to fire-and-forget).
            handleAlarmAction(sessionId);
        }
        if (log.isDebugEnabled()) {
            log.debug("Action queued: session={} verb={} arg={}",
                    sessionId, action.verb(), action.arg().orElse(""));
        }
    }

    /**
     * Enqueue an alarm for the bot's composite (if any) on the shared
     * {@link AlarmQueue}. Silently no-ops for solo entities.
     */
    private void handleAlarmAction(String sessionId) {
        var botOpt = botRegistry.getBySession(sessionId);
        if (botOpt.isEmpty()) return;
        var bot = botOpt.get();
        Cell cell = worldGrid.getCell(bot.position().x(), bot.position().y());
        if (!(cell.occupant() instanceof Entity.CompositeMember cm)) return;
        alarmQueue.enqueueAlarm(cm.compositeId(), bot.position(), currentTick);
    }

    @EventListener
    @Order(20) // After SimulationEngine(10), before PerceptionBroadcaster(50)
    public void onTick(TickEvent event) {
        this.currentTick = event.tickNumber();
        // Atomically swap in fresh maps — no window for lost actions
        var actions = pendingActions.getAndSet(new ConcurrentHashMap<>());
        var voteBallots = pendingVoteBallots.getAndSet(new ConcurrentHashMap<>());

        if (actions.isEmpty()) return;

        resolveActions(event.tickNumber(), actions, voteBallots);
    }

    /**
     * Resolve all queued actions for a tick. Package-private for testing.
     * Delegates to the 3-arg version with empty ballots.
     */
    void resolveActions(long tickNumber, Map<String, Frame.ActionFrame> actions) {
        resolveActions(tickNumber, actions, Map.of());
    }

    /**
     * Resolve all queued actions for a tick, including LOCOMOTOR vote ballots.
     */
    void resolveActions(long tickNumber, Map<String, Frame.ActionFrame> actions,
                        Map<String, String> voteBallots) {
        int moveCount = 0, consumeCount = 0, reproduceCount = 0, restCount = 0;
        int conflicts = 0;

        // Phase 1: Parse and validate actions, build resolution lists
        List<ResolvedAction> resolvedList = new ArrayList<>();
        List<ResolvedCompositeAction> resolvedCompositeList = new ArrayList<>();

        for (var entry : actions.entrySet()) {
            String sessionId = entry.getKey();
            Frame.ActionFrame action = entry.getValue();
            var botOpt = botRegistry.getBySession(sessionId);
            if (botOpt.isEmpty()) continue;
            var bot = botOpt.get();

            Cell cell = worldGrid.getCell(bot.position().x(), bot.position().y());

            if (cell.occupant() instanceof Entity.CompositeMember cm && cm.id().equals(bot.entityId())) {
                resolvedCompositeList.add(new ResolvedCompositeAction(sessionId, bot, cm, action));
                continue;
            }

            if (!(cell.occupant() instanceof Particle particle) || !particle.id().equals(bot.entityId())) {
                botRegistry.unregisterBySession(sessionId);
                continue;
            }

            resolvedList.add(new ResolvedAction(sessionId, bot, particle, action));
        }

        // Phase 2: Shuffle for fairness then resolve Particle (solo) actions
        Collections.shuffle(resolvedList, actionRng);
        Set<Position> claimedCells = new HashSet<>();

        for (ResolvedAction ra : resolvedList) {
            switch (ra.action.verb()) {
                case 'M' -> {
                    if (resolveMove(ra, claimedCells)) moveCount++; else conflicts++;
                }
                case 'E' -> {
                    resolveConsume(ra);
                    consumeCount++;
                }
                case 'R' -> {
                    if (resolveReproduce(ra, claimedCells, tickNumber)) reproduceCount++; else restCount++;
                }
                case 'A' -> {
                    // Solo attack verb — semantically equivalent to rest for non-composite
                    // Particle. Composite-member A dispatch happens in Phase 3.
                    restCount++;
                }
                case 'V', 'L' -> {
                    // V is only meaningful for LOCOMOTOR composite members (Phase 4); a
                    // solo Particle issuing V is effectively rest. L has already been
                    // routed to AlarmQueue in queueAction.
                    restCount++;
                }
                default -> restCount++;
            }
        }

        // Phase 3: Resolve composite member reactive role actions
        Collections.shuffle(resolvedCompositeList, actionRng);

        for (ResolvedCompositeAction rca : resolvedCompositeList) {
            var compositeOpt = compositeRegistry.getComposite(rca.member.compositeId());
            if (compositeOpt.isEmpty()) continue;
            var composite = compositeOpt.get();

            switch (rca.member.role()) {
                case FEEDER -> resolveFeederConsume(rca, composite);
                case ATTACKER -> resolveAttackerAttack(rca, composite, tickNumber);
                case REPRODUCER -> resolveReproducerBud(rca, composite, claimedCells);
                case DEFENDER, SENSOR -> { /* passive — no dispatch */ }
                case LOCOMOTOR -> { /* handled in Phase 4 via IRV */ }
            }
        }

        // Phase 4: Resolve composite LOCOMOTOR IRV voting and movement
        resolveCompositeMovements(resolvedCompositeList, claimedCells, voteBallots);

        if (log.isDebugEnabled()) {
            log.debug("Tick {} actions: move={}, consume={}, reproduce={}, rest={}, conflicts={}",
                    tickNumber, moveCount, consumeCount, reproduceCount, restCount, conflicts);
        }
    }

    // ── Verb dispatch helpers ────────────────────────────────────

    /** Extract the single numpad digit from an ActionFrame arg, or null if absent/invalid. */
    private static Direction directionOf(Frame.ActionFrame action) {
        if (action.arg().isEmpty()) return null;
        String arg = action.arg().get();
        if (arg.isEmpty()) return null;
        try {
            return Direction.fromNumpad(arg.charAt(0));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Move ──────────────────────────────────────────────────────

    private boolean resolveMove(ResolvedAction ra, Set<Position> claimedCells) {
        Direction dir = directionOf(ra.action);
        if (dir == null) return false;

        String entityId = ra.bot.entityId();
        boolean hasMoveBuff = entityId != null
                && buffRegistry.hasBuff(entityId, BuffRegistry.BuffType.MOVEMENT_PLUS_1);

        Position target;
        if (hasMoveBuff) {
            target = findTargetAtRange(ra.bot.position(), dir, /*range*/ 2, claimedCells,
                    worldGrid.getWidth(), worldGrid.getHeight());
            if (target == null) return false;
        } else {
            target = dir.apply(ra.bot.position(), worldGrid.getWidth(), worldGrid.getHeight());
            if (claimedCells.contains(target)) return false;
        }

        Cell targetCell = worldGrid.getCell(target.x(), target.y());
        if (targetCell.occupant() instanceof Rock) return false;
        if (targetCell.occupant() instanceof Particle) return false;
        if (targetCell.occupant() instanceof Entity.BondedPair) return false;
        if (targetCell.occupant() instanceof Entity.CompositeMember) return false;

        claimedCells.add(target);
        worldGrid.clearEntity(ra.bot.position().x(), ra.bot.position().y());

        Particle placed = ra.particle;
        if (targetCell.occupant() instanceof Nutrient) {
            var profile = metabolicProfile.forType(ra.particle.type());
            int energyGain = profile.nutrientConsumeEnergy();
            double intensity = StarvationConfig.computeIntensity(
                    ra.particle.energy(), ra.particle.maxEnergy(),
                    profile.starvationThreshold(), profile.starvationFloor());
            if (intensity > 0.0) {
                energyGain = (int) (energyGain * (1 + starvationConfig.maxNutrientBoost() * intensity));
            }
            placed = ra.particle.withEnergy(ra.particle.energy() + energyGain);
        }
        worldGrid.setEntity(target.x(), target.y(), placed);
        botRegistry.updatePosition(ra.sessionId, target);
        return true;
    }

    // ── Consume ───────────────────────────────────────────────────

    private void resolveConsume(ResolvedAction ra) {
        Position pos = ra.bot.position();
        List<Position> neighbors = worldGrid.getNeighbors(pos.x(), pos.y());
        Position nutrientPos = null;
        Nutrient nutrient = null;

        for (Position np : neighbors) {
            Cell nc = worldGrid.getCell(np.x(), np.y());
            if (nc.occupant() instanceof Nutrient n) {
                nutrientPos = np;
                nutrient = n;
                break;
            }
        }

        if (nutrient == null) return;

        var profile = metabolicProfile.forType(ra.particle.type());
        int energyGain = profile.nutrientConsumeEnergy();
        double intensity = StarvationConfig.computeIntensity(
                ra.particle.energy(), ra.particle.maxEnergy(),
                profile.starvationThreshold(), profile.starvationFloor());
        if (intensity > 0.0) {
            energyGain = (int) (energyGain * (1 + starvationConfig.maxNutrientBoost() * intensity));
        }
        Particle updated = ra.particle.withEnergy(ra.particle.energy() + energyGain);
        worldGrid.setEntity(pos.x(), pos.y(), updated);

        Nutrient depleted = nutrient.consumed(energyGain);
        if (depleted.isDepleted()) {
            worldGrid.clearEntity(nutrientPos.x(), nutrientPos.y());
        } else {
            worldGrid.setEntity(nutrientPos.x(), nutrientPos.y(), depleted);
        }
    }

    // ── Reproduce ─────────────────────────────────────────────────

    private boolean resolveReproduce(ResolvedAction ra, Set<Position> claimedCells, long tickNumber) {
        var profile = metabolicProfile.forType(ra.particle.type());
        int reproduceCost = profile.reproduceEnergyCost();

        if (ra.particle.energy() < reproduceCost) return false;

        int energyAfterCost = ra.particle.energy() - reproduceCost;
        int starvationFloor = (int) (profile.starvationThreshold() / 100.0 * ra.particle.maxEnergy());
        if (energyAfterCost < starvationFloor) return false;

        int cooldown = profile.reproduceCooldown();
        long lastTick = lastReproducedTick.getOrDefault(ra.particle.id(), Long.MIN_VALUE / 2);
        long ticksSince = tickNumber - lastTick;
        if (ticksSince < cooldown) return false;

        Direction dir = directionOf(ra.action);
        if (dir == null) return false;

        int range = profile.reproduceRange();
        Position target = findTargetAtRange(ra.bot.position(), dir, range, claimedCells,
                worldGrid.getWidth(), worldGrid.getHeight());
        if (target == null) return false;

        claimedCells.add(target);
        String childId = "child-" + childIdCounter.incrementAndGet();
        Particle child = new Particle(childId, ra.particle.type(),
                profile.childStartEnergy(), profile.maxEnergy());
        worldGrid.setEntity(target.x(), target.y(), child);

        Particle updatedParent = ra.particle.withEnergy(ra.particle.energy() - reproduceCost);
        worldGrid.setEntity(ra.bot.position().x(), ra.bot.position().y(), updatedParent);

        lastReproducedTick.put(ra.particle.id(), tickNumber);
        if (profile.bonusOffspringChance() > 0.0
                && actionRng.nextDouble() < profile.bonusOffspringChance()) {
            Position bonusTarget = findEmptyAdjacentCell(target, claimedCells);
            if (bonusTarget != null) {
                String bonusChildId = "child-" + childIdCounter.incrementAndGet();
                Particle bonusChild = new Particle(bonusChildId, ra.particle.type(),
                        profile.childStartEnergy(), profile.maxEnergy());
                worldGrid.setEntity(bonusTarget.x(), bonusTarget.y(), bonusChild);
                claimedCells.add(bonusTarget);
            }
        }

        return true;
    }

    /** Return the first empty, unclaimed neighbor of the given position, or null. */
    private Position findEmptyAdjacentCell(Position pos, Set<Position> claimedCells) {
        for (Position np : worldGrid.getNeighbors(pos.x(), pos.y())) {
            if (claimedCells.contains(np)) continue;
            if (worldGrid.getCell(np.x(), np.y()).isEmpty()) return np;
        }
        return null;
    }

    // ── Composite reactive role actions ─────────────────────────

    /**
     * FEEDER consume: find adjacent nutrient, consume it, add energy to shared pool (D-15).
     */
    private void resolveFeederConsume(ResolvedCompositeAction rca,
                                      CompositeRegistry.CompositeState composite) {
        Position pos = rca.bot.position();
        List<Position> neighbors = worldGrid.getNeighbors(pos.x(), pos.y());
        Position nutrientPos = null;
        Nutrient nutrient = null;

        for (Position np : neighbors) {
            Cell nc = worldGrid.getCell(np.x(), np.y());
            if (nc.occupant() instanceof Nutrient n) {
                nutrientPos = np;
                nutrient = n;
                break;
            }
        }

        if (nutrient == null) return;

        var feederProfile = metabolicProfile.forType(rca.member.type());
        int energyGain = feederProfile.nutrientConsumeEnergy();
        double feederIntensity = StarvationConfig.computeIntensity(
                rca.member.energy(), rca.member.maxEnergy(),
                feederProfile.starvationThreshold(), feederProfile.starvationFloor());
        if (feederIntensity > 0.0) {
            energyGain = (int) (energyGain * (1 + starvationConfig.maxNutrientBoost() * feederIntensity));
        }
        composite.addEnergy(energyGain);

        Nutrient depleted = nutrient.consumed(energyGain);
        if (depleted.isDepleted()) {
            worldGrid.clearEntity(nutrientPos.x(), nutrientPos.y());
        } else {
            worldGrid.setEntity(nutrientPos.x(), nutrientPos.y(), depleted);
        }

        int feederDrained = composite.drainEnergy(compositeConfig.feederActiveDrain());
        if (feederDrained < compositeConfig.feederActiveDrain()) {
            log.debug("Partial feeder active drain: {} of {} for composite {}",
                    feederDrained, compositeConfig.feederActiveDrain(), rca.member.compositeId());
        }
    }

    /**
     * ATTACKER attack: find adjacent enemy entity, apply true damage (D-10).
     */
    private void resolveAttackerAttack(ResolvedCompositeAction rca,
                                        CompositeRegistry.CompositeState composite,
                                        long tickNumber) {
        Position pos = rca.bot.position();

        Direction dir = directionOf(rca.action);
        Position targetPos;
        if (dir != null) {
            targetPos = dir.apply(pos, worldGrid.getWidth(), worldGrid.getHeight());
        } else {
            targetPos = findAdjacentEnemy(pos, rca.member.compositeId());
            if (targetPos == null) return;
        }

        Cell targetCell = worldGrid.getCell(targetPos.x(), targetPos.y());
        Entity target = targetCell.occupant();
        if (target == null) return;

        int baseDamage = config.combatEnergyTransfer();
        if (buffRegistry.hasBuff(rca.member.id(), BuffRegistry.BuffType.ATTACK_PLUS_1)) {
            baseDamage += 1;
        }
        int damage = baseDamage;

        if (target instanceof Particle p) {
            Particle damaged = p.withEnergy(p.energy() - damage);
            worldGrid.setEntity(targetPos.x(), targetPos.y(), damaged);
        } else if (target instanceof Entity.CompositeMember cm
                && !cm.compositeId().equals(rca.member.compositeId())) {
            Entity.CompositeMember damaged = cm.withEnergy(cm.energy() - damage);
            worldGrid.setEntity(targetPos.x(), targetPos.y(), damaged);
        } else if (target instanceof Entity.BondedPair bp) {
            Entity.BondedPair damaged = bp.withEnergy(bp.energy() - damage);
            worldGrid.setEntity(targetPos.x(), targetPos.y(), damaged);
        } else {
            return;
        }

        if (environmentEngine != null) {
            int splash = environmentEngine.computeSplashDamage(targetPos);
            if (splash > 0) {
                Cell ac = worldGrid.getCell(pos.x(), pos.y());
                if (ac.occupant() instanceof Entity.CompositeMember attackerMember
                        && attackerMember.id().equals(rca.member.id())) {
                    int clampedEnergy = Math.max(0, attackerMember.energy() - splash);
                    worldGrid.setEntity(pos.x(), pos.y(),
                            attackerMember.withEnergy(clampedEnergy));
                    environmentEngine.markEnvDamageApplied();
                }
            }
            String defenderId = EntityIds.entityIdOf(target);
            if (defenderId != null && environmentEngine.isInfected(defenderId)) {
                environmentEngine.reduceInfection(defenderId, environmentEngine.getAttackCureReduction(),
                        tickNumber, targetPos);
            }
        }

        int attackerDrained = composite.drainEnergy(compositeConfig.attackerActiveDrain());
        if (attackerDrained < compositeConfig.attackerActiveDrain()) {
            log.debug("Partial attacker active drain: {} of {} for composite {}",
                    attackerDrained, compositeConfig.attackerActiveDrain(), rca.member.compositeId());
        }
    }

    /** Find the first adjacent enemy entity (non-member of this composite). */
    private Position findAdjacentEnemy(Position pos, String ownCompositeId) {
        List<Position> neighbors = worldGrid.getNeighbors(pos.x(), pos.y());
        for (Position np : neighbors) {
            Cell nc = worldGrid.getCell(np.x(), np.y());
            Entity occ = nc.occupant();
            if (occ instanceof Particle) return np;
            if (occ instanceof Entity.BondedPair) return np;
            if (occ instanceof Entity.CompositeMember cm && !cm.compositeId().equals(ownCompositeId)) return np;
        }
        return null;
    }

    /** REPRODUCER bud: spawn a solo Particle into an adjacent empty cell (D-32). */
    private void resolveReproducerBud(ResolvedCompositeAction rca,
                                       CompositeRegistry.CompositeState composite,
                                       Set<Position> claimedCells) {
        var profile = metabolicProfile.forType(rca.member.type());
        int reproduceCost = profile.reproduceEnergyCost();

        if (composite.getSharedPoolEnergy() < reproduceCost) return;

        int poolAfterCost = composite.getSharedPoolEnergy() - reproduceCost;
        int poolStarvationFloor =
                (int) (profile.starvationThreshold() / 100.0 * composite.getMaxPoolEnergy());
        if (poolAfterCost < poolStarvationFloor) return;

        Direction dir = directionOf(rca.action);
        if (dir == null) return;

        Position target = dir.apply(rca.bot.position(), worldGrid.getWidth(), worldGrid.getHeight());
        if (claimedCells.contains(target)) return;

        Cell targetCell = worldGrid.getCell(target.x(), target.y());
        if (targetCell.hasOccupant()) return;

        claimedCells.add(target);
        String childId = "child-" + childIdCounter.incrementAndGet();
        Particle child = new Particle(childId, rca.member.type(),
                profile.childStartEnergy(), profile.maxEnergy());
        worldGrid.setEntity(target.x(), target.y(), child);

        int reproduceCostDrained = composite.drainEnergy(reproduceCost);
        if (reproduceCostDrained < reproduceCost) {
            log.debug("Partial reproduce cost drain: {} of {} for composite {}",
                    reproduceCostDrained, reproduceCost, rca.member.compositeId());
        }

        int reproducerDrained = composite.drainEnergy(compositeConfig.reproducerActiveDrain());
        if (reproducerDrained < compositeConfig.reproducerActiveDrain()) {
            log.debug("Partial reproducer active drain: {} of {} for composite {}",
                    reproducerDrained, compositeConfig.reproducerActiveDrain(), rca.member.compositeId());
        }
    }

    // ── Composite LOCOMOTOR IRV voting and movement ──────────────

    /**
     * Resolve coordinated composite movement via LOCOMOTOR IRV voting (plan
     * 15-06 Task 2; replaces plurality per SCHEMA §8.6 new-scope #8). Ballots
     * are raw 3-char numpad strings captured from verb-V {@code ActionFrame.arg()}.
     */
    private void resolveCompositeMovements(List<ResolvedCompositeAction> compositeActions,
                                            Set<Position> claimedCells,
                                            Map<String, String> voteBallots) {
        // Group ballots by compositeId. Each LOCOMOTOR member contributes one
        // ballot (captured on queueAction). Fall back to derived single-choice
        // ballot from verb-M's numpad for LOCOMOTOR members whose action was not V.
        Map<String, List<String>> compositeBallots = new HashMap<>();
        for (ResolvedCompositeAction rca : compositeActions) {
            if (rca.member.role() == Entity.Role.LOCOMOTOR) {
                String compositeId = rca.member.compositeId();
                String ballot = voteBallots.get(rca.sessionId);
                if (ballot == null && rca.action.verb() == 'M' && rca.action.arg().isPresent()) {
                    ballot = rca.action.arg().get();
                }
                if (ballot != null && !ballot.isEmpty()) {
                    compositeBallots.computeIfAbsent(compositeId, k -> new ArrayList<>()).add(ballot);
                }
            }
        }

        // Increment ticks-since-move for all tracked composites
        for (String compositeId : compositeTicksSinceMove.keySet()) {
            compositeTicksSinceMove.merge(compositeId, 1, Integer::sum);
        }

        // Process each composite with ballots
        for (var entry : compositeBallots.entrySet()) {
            String compositeId = entry.getKey();
            List<String> ballots = entry.getValue();

            var compositeOpt = compositeRegistry.getComposite(compositeId);
            if (compositeOpt.isEmpty()) continue;
            var composite = compositeOpt.get();

            Direction direction = resolveLocomotorVote(ballots);
            if (direction == null) continue;

            int locomotorCount = countLocomotors(composite);
            int colonySize = composite.getMemberCount();
            double speed = (double) locomotorCount / colonySize * compositeConfig.speedConstant();
            int moveInterval = speed >= 1.0 ? 1 : (int) Math.ceil(1.0 / speed);
            int effectiveInterval = hasAnyLocomotorMovementBuff(composite)
                    ? Math.max(1, moveInterval - 1)
                    : moveInterval;
            int ticksSince = compositeTicksSinceMove.getOrDefault(compositeId, effectiveInterval);
            if (ticksSince < effectiveInterval) continue;

            boolean moved = executeCompositeMovement(compositeId, direction, composite, claimedCells);
            if (moved) {
                compositeTicksSinceMove.put(compositeId, 0);
                int locomotorCost = locomotorCount * compositeConfig.locomotorActiveDrain();
                int locomotorDrained = composite.drainEnergy(locomotorCost);
                if (locomotorDrained < locomotorCost) {
                    log.debug("Partial locomotor active drain: {} of {} for composite {}",
                            locomotorDrained, locomotorCost, compositeId);
                }
            }
        }

        // Prune stale entries for dissolved composites to prevent memory leak (WR-03)
        Set<String> activeCompositeIds = new HashSet<>();
        for (var composite : compositeRegistry.getAll()) {
            activeCompositeIds.add(composite.getCompositeId());
        }
        compositeTicksSinceMove.keySet().retainAll(activeCompositeIds);

        Set<String> activeEntityIds = new HashSet<>();
        for (var bot : botRegistry.getAllBots()) {
            activeEntityIds.add(bot.entityId());
        }
        lastReproducedTick.keySet().retainAll(activeEntityIds);
    }

    /**
     * IRV (Instant Runoff) resolver for LOCOMOTOR votes. Ballots are 3-char
     * numpad strings per SCHEMA §8.6 (e.g. {@code "493"} = 1st=NE, 2nd=W, 3rd=SE).
     * Elimination ties broken by lowest numpad digit.
     *
     * <p>Algorithm (SCHEMA §9 new-scope addition #8):
     * <ol>
     *   <li>Parse each ballot into an ordered list of directions.</li>
     *   <li>Count first-preferences among {@code active} directions.</li>
     *   <li>If a direction has strict majority ({@code count > cast/2}), return it.</li>
     *   <li>Otherwise eliminate the direction(s) with the fewest first-preferences.
     *       Tie broken by lowest numpad digit (7=NW is lowest … 3=SE is highest).</li>
     *   <li>Re-tabulate with eliminated direction removed; ballots whose top choices
     *       are all eliminated become exhausted and do not count.</li>
     *   <li>Repeat until a winner emerges or all ballots are exhausted (return null).</li>
     * </ol>
     *
     * <p>Visibility is intentionally {@code static package-private} — same-package
     * tests ({@code IRVVoteResolverTest}) call it directly without constructing a
     * resolver instance. Not part of the public contract.
     */
    static Direction resolveLocomotorVote(List<String> rankedNumpad) {
        if (rankedNumpad == null || rankedNumpad.isEmpty()) return null;

        List<List<Direction>> ballots = new ArrayList<>();
        for (String ranks : rankedNumpad) {
            if (ranks == null || ranks.isBlank()) continue;
            List<Direction> ballot = new ArrayList<>(ranks.length());
            for (int i = 0; i < ranks.length(); i++) {
                char ch = ranks.charAt(i);
                if (ch < '1' || ch > '9') continue;
                Direction d = Direction.fromNumpad(ch);
                if (d != null) ballot.add(d);
            }
            if (!ballot.isEmpty()) ballots.add(ballot);
        }
        if (ballots.isEmpty()) return null;

        Set<Direction> active = EnumSet.allOf(Direction.class);
        while (true) {
            Map<Direction, Integer> counts = new EnumMap<>(Direction.class);
            int cast = 0;
            for (List<Direction> ballot : ballots) {
                for (Direction d : ballot) {
                    if (active.contains(d)) {
                        counts.merge(d, 1, Integer::sum);
                        cast++;
                        break;
                    }
                }
            }
            if (cast == 0) return null;

            int threshold = cast / 2;
            for (var e : counts.entrySet()) {
                if (e.getValue() > threshold) return e.getKey();
            }

            int min = Integer.MAX_VALUE;
            for (Direction d : active) {
                int c = counts.getOrDefault(d, 0);
                if (c < min) min = c;
            }
            Direction toEliminate = null;
            for (Direction d : active) {
                if (counts.getOrDefault(d, 0) == min) {
                    if (toEliminate == null || Direction.numpadOf(d) < Direction.numpadOf(toEliminate)) {
                        toEliminate = d;
                    }
                }
            }
            if (toEliminate == null || active.size() <= 1) {
                return active.iterator().next();
            }
            active.remove(toEliminate);
        }
    }

    /** Execute rigid body movement for a composite — all members shift in the same direction (D-24). */
    private boolean executeCompositeMovement(String compositeId, Direction dir,
                                              CompositeRegistry.CompositeState composite,
                                              Set<Position> claimedCells) {
        List<String> memberIds = composite.getMemberIds();
        List<Position> currentPositions = new ArrayList<>();
        List<Entity.CompositeMember> members = new ArrayList<>();

        for (String memberId : memberIds) {
            Position pos = composite.getPositionForMember(memberId);
            if (pos == null) continue;
            Cell cell = worldGrid.getCell(pos.x(), pos.y());
            if (cell.occupant() instanceof Entity.CompositeMember cm && cm.id().equals(memberId)) {
                currentPositions.add(pos);
                members.add(cm);
            }
        }

        if (members.isEmpty()) return false;

        List<Position> targetPositions = currentPositions.stream()
                .map(p -> dir.apply(p, worldGrid.getWidth(), worldGrid.getHeight()))
                .toList();

        Set<Position> currentPosSet = new HashSet<>(currentPositions);
        for (Position target : targetPositions) {
            if (claimedCells.contains(target)) return false;
            if (!currentPosSet.contains(target)) {
                Cell tc = worldGrid.getCell(target.x(), target.y());
                if (tc.hasOccupant()) return false;
            }
        }

        claimedCells.addAll(targetPositions);

        for (Position pos : currentPositions) {
            worldGrid.clearEntity(pos.x(), pos.y());
        }
        Map<String, Position> newPositions = new HashMap<>();
        for (int i = 0; i < targetPositions.size(); i++) {
            Position target = targetPositions.get(i);
            Entity.CompositeMember member = members.get(i);
            worldGrid.setEntity(target.x(), target.y(), member);

            botRegistry.getSessionForEntity(member.id()).ifPresent(sid ->
                    botRegistry.updatePosition(sid, target));
            newPositions.put(member.id(), target);
        }

        compositeRegistry.updateMemberPositions(compositeId, newPositions);
        return true;
    }

    /** Count LOCOMOTOR members in a composite by scanning the grid. */
    private int countLocomotors(CompositeRegistry.CompositeState composite) {
        int count = 0;
        for (String memberId : composite.getMemberIds()) {
            Position pos = composite.getPositionForMember(memberId);
            if (pos != null) {
                Cell cell = worldGrid.getCell(pos.x(), pos.y());
                if (cell.occupant() instanceof Entity.CompositeMember cm
                        && cm.role() == Entity.Role.LOCOMOTOR) {
                    count++;
                }
            }
        }
        return count;
    }

    // ── Internal ──────────────────────────────────────────────────

    private record ResolvedAction(
            String sessionId,
            BotRegistry.BotState bot,
            Particle particle,
            Frame.ActionFrame action
    ) {}

    private record ResolvedCompositeAction(
            String sessionId,
            BotRegistry.BotState bot,
            Entity.CompositeMember member,
            Frame.ActionFrame action
    ) {}
}
