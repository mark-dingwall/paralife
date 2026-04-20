package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.codec.Frame;
import com.paralife.websocket.Messages;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects bot actions between ticks and resolves them atomically during tick processing.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Bots submit actions via {@link #queueAction} (called from WebSocket handler)</li>
 *   <li>On tick @Order(20), all queued actions are drained and resolved</li>
 *   <li>Results sent back to each bot as {@link Messages.ActionResult}</li>
 * </ol>
 *
 * <p>Conflict resolution: when multiple entities target the same cell,
 * the queue is shuffled and the first entity wins; others fall back to rest.
 */
@Component
public class ActionResolver {

    private static final Logger log = LoggerFactory.getLogger(ActionResolver.class);

    /**
     * Legacy flat reproduce cost shared by {@link ActionResolverTest} and
     * {@link CompositeActionTest} as a canonical fixture value — those tests
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
    private final ObjectMapper objectMapper;
    private final CompositeRegistry compositeRegistry;
    private final CompositeConfig compositeConfig;
    private final MetabolicProfile metabolicProfile;
    private final StarvationConfig starvationConfig;
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
     * Pending action: sessionId → action. Only the last action per session per tick is kept.
     * Uses AtomicReference swap for atomic drain — see {@link #onTick}.
     */
    private final AtomicReference<ConcurrentHashMap<String, Messages.Action>> pendingActions =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /**
     * Pending ranked preferences from LOCOMOTOR composite members.
     * Keyed by sessionId, drained alongside pendingActions on tick.
     */
    private final AtomicReference<ConcurrentHashMap<String, List<String>>> pendingRankedPreferences =
            new AtomicReference<>(new ConcurrentHashMap<>());

    @Autowired
    public ActionResolver(WorldGrid worldGrid, BotRegistry botRegistry,
                           SessionRegistry sessionRegistry, SimulationConfig config,
                           ObjectMapper objectMapper,
                           CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                           MetabolicProfile metabolicProfile,
                           StarvationConfig starvationConfig) {
        this.worldGrid = worldGrid;
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.config = config;
        this.objectMapper = objectMapper;
        this.compositeRegistry = compositeRegistry;
        this.compositeConfig = compositeConfig;
        this.metabolicProfile = metabolicProfile;
        this.starvationConfig = starvationConfig;
    }

    /**
     * Convenience constructor for tests predating Phase 13 Plan 02 that don't
     * need starvation modifiers (falls back to {@link StarvationConfig#defaults()}).
     */
    public ActionResolver(WorldGrid worldGrid, BotRegistry botRegistry,
                           SessionRegistry sessionRegistry, SimulationConfig config,
                           ObjectMapper objectMapper,
                           CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                           MetabolicProfile metabolicProfile) {
        this(worldGrid, botRegistry, sessionRegistry, config, objectMapper,
                compositeRegistry, compositeConfig, metabolicProfile, StarvationConfig.defaults());
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
     * Spring wires this automatically via {@link Autowired}; {@code required=false}
     * keeps pre-Phase-14 unit tests that never set this field working as-is
     * (they see an empty registry instead of null — no buff effects fire).
     */
    @Autowired(required = false)
    public void setBuffRegistry(BuffRegistry buffRegistry) {
        if (buffRegistry != null) this.buffRegistry = buffRegistry;
    }

    /** Package-private read accessor for tests. */
    BuffRegistry getBuffRegistry() {
        return buffRegistry;
    }

    /**
     * Plan 14-05: walk {@code range} steps in {@code dir} from {@code from},
     * falling back one step at a time if the far cell is blocked. Returns
     * the first empty, unclaimed cell at range ≤ {@code range}, or null if
     * every candidate is blocked. FN-9 fallback: preserves the SPORE
     * reproduce-range=2 semantic that a range-2 target being blocked falls
     * back to range-1 (the same fallback is used for MOVEMENT_PLUS_1 moves).
     *
     * <p>Package-private so tests can drive the helper directly.
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

    /**
     * Plan 14-05 cycle-6 MEDIUM #10 — iterate ONLY LOCOMOTOR members when
     * checking for MOVEMENT_PLUS_1. A buff on a non-LOCOMOTOR member MUST
     * NOT trigger the reduced moveInterval — the buff only affects cadence
     * when it lives on a role that actually moves.
     *
     * <p>Package-private so
     * {@code hasAnyLocomotorMovementBuffSkipsNonLocomotorMembers} can drive
     * the helper directly.
     */
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

    /**
     * Queue an action from a bot. Replaces any previous action for the same session this tick.
     */
    public void queueAction(String sessionId, Messages.Action action) {
        pendingActions.get().put(sessionId, action);
        log.debug("Action queued: session={} type={} dir={}", sessionId,
                action.actionType(), action.direction());
    }

    /**
     * Plan 15-06 Task 1 transitional overload — accepts {@link Frame.ActionFrame}
     * from the codec-driven {@code WorldWebSocketHandler}. Task 2 rewrites the
     * whole verb-dispatch pipeline around {@code Frame.ActionFrame}; for Task 1
     * this adapter keeps production compile green by translating the incoming
     * frame to the legacy {@link Messages.Action} shape used by the existing
     * {@link #resolveActions} pipeline.
     *
     * <p>Task 2 Part B replaces this overload with the full verb-dispatch
     * implementation per SCHEMA §8.6 plus IRV + alarm routing.
     */
    public void queueAction(String sessionId, Frame.ActionFrame frame) {
        Messages.Action legacy = translateToLegacy(frame);
        if (legacy == null) {
            log.debug("Dropping action with unmapped verb={}: session={}", frame.verb(), sessionId);
            return;
        }
        pendingActions.get().put(sessionId, legacy);
        log.debug("Action queued (from frame): session={} verb={}", sessionId, frame.verb());
    }

    /**
     * Transitional — translate the Task 1 {@link Frame.ActionFrame} into the
     * legacy {@link Messages.Action} shape. Retired by Task 2 Part B.
     */
    private static Messages.Action translateToLegacy(Frame.ActionFrame frame) {
        String dir = frame.arg().map(ActionResolver::numpadToDirectionName).orElse(null);
        return switch (frame.verb()) {
            case 'M' -> new Messages.Action("move", dir);
            case 'E' -> new Messages.Action("consume", dir);
            case 'A' -> new Messages.Action("attack", dir);
            case 'R' -> new Messages.Action("reproduce", dir);
            case 'V' -> new Messages.Action("move", dir); // LOCOMOTOR vote — Task 2 re-routes
            case 'L' -> new Messages.Action("rest", null); // alarm — Task 2 routes to AlarmQueue
            default -> null;
        };
    }

    /** Numpad digit → legacy Direction.name(); '5' (self) returns null. */
    private static String numpadToDirectionName(String arg) {
        if (arg == null || arg.isEmpty()) return null;
        return switch (arg.charAt(0)) {
            case '7' -> "NW";
            case '8' -> "N";
            case '9' -> "NE";
            case '4' -> "W";
            case '5' -> null;
            case '6' -> "E";
            case '1' -> "SW";
            case '2' -> "S";
            case '3' -> "SE";
            default -> null;
        };
    }

    /**
     * Queue a composite action from a composite member bot.
     * Stores the action (as a regular Action) and separately stores ranked preferences
     * for LOCOMOTOR STV voting.
     */
    public void queueCompositeAction(String sessionId, Messages.CompositeAction action) {
        // Convert to regular Action for unified processing
        pendingActions.get().put(sessionId, new Messages.Action(action.actionType(), action.direction()));
        // Store ranked preferences separately for LOCOMOTOR voting
        if (action.rankedPreferences() != null && !action.rankedPreferences().isEmpty()) {
            pendingRankedPreferences.get().put(sessionId, action.rankedPreferences());
        }
        log.debug("Composite action queued: session={} type={} dir={} prefs={}",
                sessionId, action.actionType(), action.direction(), action.rankedPreferences());
    }

    @EventListener
    @Order(20) // After SimulationEngine(10), before PerceptionBroadcaster(50)
    public void onTick(TickEvent event) {
        // Atomically swap in fresh maps — no window for lost actions
        var actions = pendingActions.getAndSet(new ConcurrentHashMap<>());
        var rankedPrefs = pendingRankedPreferences.getAndSet(new ConcurrentHashMap<>());

        if (actions.isEmpty()) return;

        resolveActions(event.tickNumber(), actions, rankedPrefs);
    }

    /**
     * Resolve all queued actions for a tick. Public for testing.
     * Delegates to the 3-arg version with empty ranked preferences.
     */
    void resolveActions(long tickNumber, Map<String, Messages.Action> actions) {
        resolveActions(tickNumber, actions, Map.of());
    }

    /**
     * Resolve all queued actions for a tick, including LOCOMOTOR ranked preferences.
     */
    void resolveActions(long tickNumber, Map<String, Messages.Action> actions,
                        Map<String, List<String>> rankedPreferences) {
        int moveCount = 0, consumeCount = 0, reproduceCount = 0, restCount = 0;
        int conflicts = 0;

        // Phase 1: Parse and validate actions, build resolution lists
        List<ResolvedAction> resolvedList = new ArrayList<>();
        List<ResolvedCompositeAction> resolvedCompositeList = new ArrayList<>();

        for (var entry : actions.entrySet()) {
            String sessionId = entry.getKey();
            Messages.Action action = entry.getValue();
            var botOpt = botRegistry.getBySession(sessionId);
            if (botOpt.isEmpty()) {
                sendResult(sessionId, tickNumber, false, action.actionType(), "Not registered");
                continue;
            }
            var bot = botOpt.get();

            // Check the entity is still alive on the grid
            Cell cell = worldGrid.getCell(bot.position().x(), bot.position().y());

            // Check if entity is a CompositeMember
            if (cell.occupant() instanceof Entity.CompositeMember cm && cm.id().equals(bot.entityId())) {
                resolvedCompositeList.add(new ResolvedCompositeAction(sessionId, bot, cm, action));
                continue;
            }

            if (!(cell.occupant() instanceof Particle particle) || !particle.id().equals(bot.entityId())) {
                sendResult(sessionId, tickNumber, false, action.actionType(), "Entity no longer alive");
                botRegistry.unregisterBySession(sessionId);
                continue;
            }

            resolvedList.add(new ResolvedAction(sessionId, bot, particle, action));
        }

        // Phase 2: Shuffle for fairness then resolve Particle actions
        Collections.shuffle(resolvedList);

        // Track cells claimed by moves this tick to detect conflicts
        Set<Position> claimedCells = new HashSet<>();

        for (ResolvedAction ra : resolvedList) {
            String actionType = ra.action.actionType() != null ? ra.action.actionType().toLowerCase() : "rest";

            switch (actionType) {
                case "move" -> {
                    var result = resolveMove(ra, claimedCells, tickNumber);
                    if (result) moveCount++; else conflicts++;
                }
                case "consume" -> {
                    resolveConsume(ra, tickNumber);
                    consumeCount++;
                }
                case "reproduce" -> {
                    var result = resolveReproduce(ra, claimedCells, tickNumber);
                    if (result) reproduceCount++; else restCount++;
                }
                case "rest" -> {
                    sendResult(ra.sessionId, tickNumber, true, "rest", "Resting");
                    restCount++;
                }
                default -> {
                    sendResult(ra.sessionId, tickNumber, false, actionType, "Unknown action type");
                    restCount++;
                }
            }
        }

        // Phase 3: Resolve composite member reactive role actions
        Collections.shuffle(resolvedCompositeList);

        for (ResolvedCompositeAction rca : resolvedCompositeList) {
            var compositeOpt = compositeRegistry.getComposite(rca.member.compositeId());
            if (compositeOpt.isEmpty()) {
                sendResult(rca.sessionId, tickNumber, false, "composite", "Composite not found");
                continue;
            }
            var composite = compositeOpt.get();

            switch (rca.member.role()) {
                case FEEDER -> resolveFeederConsume(rca, composite, tickNumber);
                case ATTACKER -> resolveAttackerAttack(rca, composite, claimedCells, tickNumber);
                case REPRODUCER -> resolveReproducerBud(rca, composite, claimedCells, tickNumber);
                case DEFENDER, SENSOR -> sendResult(rca.sessionId, tickNumber, true, "rest", "Passive role");
                case LOCOMOTOR -> {} // Handled separately in STV voting (Task 2)
            }
        }

        // Phase 4: Resolve composite LOCOMOTOR STV voting and movement
        resolveCompositeMovements(resolvedCompositeList, claimedCells, tickNumber, rankedPreferences);

        if (log.isDebugEnabled()) {
            log.debug("Tick {} actions: move={}, consume={}, reproduce={}, rest={}, conflicts={}",
                    tickNumber, moveCount, consumeCount, reproduceCount, restCount, conflicts);
        }
    }

    // ── Move ──────────────────────────────────────────────────────

    private boolean resolveMove(ResolvedAction ra, Set<Position> claimedCells, long tickNumber) {
        Direction dir = Direction.fromString(ra.action.direction());
        if (dir == null) {
            sendResult(ra.sessionId, tickNumber, false, "move", "Invalid direction");
            return false;
        }

        // Plan 14-05: MOVEMENT_PLUS_1 enables a 2-cell hop for SOLO Particle
        // AND BondedPair (cycle-6 HIGH #3). cycle-9 action C.1: use
        // bot.entityId() directly — returns Particle.id() for Particle-bound
        // bots and bp.id() for BondedPair-bound bots. FN-9 fallback preserved
        // via findTargetAtRange so a range-2 target being blocked falls back
        // to range-1.
        String entityId = ra.bot.entityId();
        boolean hasMoveBuff = entityId != null
                && buffRegistry.hasBuff(entityId, BuffRegistry.BuffType.MOVEMENT_PLUS_1);

        Position target;
        if (hasMoveBuff) {
            target = findTargetAtRange(ra.bot.position(), dir, /*range*/ 2, claimedCells,
                    worldGrid.getWidth(), worldGrid.getHeight());
            if (target == null) {
                sendResult(ra.sessionId, tickNumber, false, "move", "Cell claimed or blocked at all ranges");
                return false;
            }
        } else {
            // Legacy single-step move — preserves pre-Phase-14 failure-reason texts.
            target = dir.apply(ra.bot.position(), worldGrid.getWidth(), worldGrid.getHeight());
            if (claimedCells.contains(target)) {
                sendResult(ra.sessionId, tickNumber, false, "move", "Cell claimed by another entity");
                return false;
            }
        }

        Cell targetCell = worldGrid.getCell(target.x(), target.y());
        if (targetCell.occupant() instanceof Rock) {
            sendResult(ra.sessionId, tickNumber, false, "move", "Cannot move into rock");
            return false;
        }
        if (targetCell.occupant() instanceof Particle) {
            sendResult(ra.sessionId, tickNumber, false, "move", "Cell occupied by another entity");
            return false;
        }
        if (targetCell.occupant() instanceof Entity.BondedPair) {
            sendResult(ra.sessionId, tickNumber, false, "move", "Cell occupied by a bonded pair");
            return false;
        }
        if (targetCell.occupant() instanceof Entity.CompositeMember) {
            sendResult(ra.sessionId, tickNumber, false, "move", "Cell occupied by a composite member");
            return false;
        }

        // Execute move
        claimedCells.add(target);
        worldGrid.clearEntity(ra.bot.position().x(), ra.bot.position().y());

        // If target has a nutrient, auto-consume on move — parity with resolveConsume
        // (per-type gain + starvation boost). Overwriting the nutrient without
        // granting energy would be a silent drop (WR-02).
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

        sendResult(ra.sessionId, tickNumber, true, "move", "Moved " + dir.name());
        return true;
    }

    // ── Consume ───────────────────────────────────────────────────

    private void resolveConsume(ResolvedAction ra, long tickNumber) {
        Position pos = ra.bot.position();

        // Check for nutrient at current position (cell occupant can't be nutrient if particle is there)
        // So check adjacent cells for nutrients
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

        if (nutrient == null) {
            sendResult(ra.sessionId, tickNumber, false, "consume", "No nutrient nearby");
            return;
        }

        // Consume the nutrient — per-type gain (Phase 13 D-02)
        var profile = metabolicProfile.forType(ra.particle.type());
        int energyGain = profile.nutrientConsumeEnergy();
        // Plan 02 D-10: starvation nutrient boost from CURRENT energy (not FLAG_STARVING).
        double intensity = StarvationConfig.computeIntensity(
                ra.particle.energy(), ra.particle.maxEnergy(),
                profile.starvationThreshold(), profile.starvationFloor());
        if (intensity > 0.0) {
            energyGain = (int) (energyGain * (1 + starvationConfig.maxNutrientBoost() * intensity));
        }
        Particle updated = ra.particle.withEnergy(ra.particle.energy() + energyGain);
        worldGrid.setEntity(pos.x(), pos.y(), updated);

        // Deplete nutrient
        Nutrient depleted = nutrient.consumed(energyGain);
        if (depleted.isDepleted()) {
            worldGrid.clearEntity(nutrientPos.x(), nutrientPos.y());
        } else {
            worldGrid.setEntity(nutrientPos.x(), nutrientPos.y(), depleted);
        }

        sendResult(ra.sessionId, tickNumber, true, "consume",
                "Consumed nutrient, energy: " + updated.energy());
    }

    // ── Reproduce ─────────────────────────────────────────────────

    private boolean resolveReproduce(ResolvedAction ra, Set<Position> claimedCells, long tickNumber) {
        // Per-type reproduce profile (Phase 13 D-02, D-16, D-18)
        var profile = metabolicProfile.forType(ra.particle.type());
        int reproduceCost = profile.reproduceEnergyCost();

        // Baseline: must have at least the reproduce cost
        if (ra.particle.energy() < reproduceCost) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce",
                    "Not enough energy (need " + reproduceCost + ", have " + ra.particle.energy() + ")");
            return false;
        }

        // D-16 surplus gate: post-cost energy must remain above the starvation threshold
        int energyAfterCost = ra.particle.energy() - reproduceCost;
        int starvationFloor = (int) (profile.starvationThreshold() / 100.0 * ra.particle.maxEnergy());
        if (energyAfterCost < starvationFloor) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce",
                    "Would starve after reproduction (surplus " + energyAfterCost
                            + " < threshold " + starvationFloor + ")");
            return false;
        }

        // Per-type cooldown gate (Phase 13)
        int cooldown = profile.reproduceCooldown();
        long lastTick = lastReproducedTick.getOrDefault(ra.particle.id(), Long.MIN_VALUE / 2);
        long ticksSince = tickNumber - lastTick;
        if (ticksSince < cooldown) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce",
                    "Reproduce cooldown (" + (cooldown - ticksSince) + " ticks remaining)");
            return false;
        }

        Direction dir = Direction.fromString(ra.action.direction());
        if (dir == null) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce", "Invalid direction");
            return false;
        }

        // D-18: walk `reproduceRange` steps in the given direction (SPORE=2, others=1).
        // FN-9: for range > 1 fall back one step closer if the far cell is blocked, so
        // SPORE does not become sterile in dense neighborhoods.
        // Plan 14-05: extracted into shared {@link #findTargetAtRange} helper so
        // resolveMove's MOVEMENT_PLUS_1 path reuses the same range-walking logic.
        int range = profile.reproduceRange();
        Position target = findTargetAtRange(ra.bot.position(), dir, range, claimedCells,
                worldGrid.getWidth(), worldGrid.getHeight());

        if (target == null) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce", "Target cell is occupied");
            return false;
        }

        // Spawn child with per-type start energy and max energy
        claimedCells.add(target);
        String childId = "child-" + childIdCounter.incrementAndGet();
        Particle child = new Particle(childId, ra.particle.type(),
                profile.childStartEnergy(), profile.maxEnergy());
        worldGrid.setEntity(target.x(), target.y(), child);

        // Deduct parent energy
        Particle updatedParent = ra.particle.withEnergy(ra.particle.energy() - reproduceCost);
        worldGrid.setEntity(ra.bot.position().x(), ra.bot.position().y(), updatedParent);

        // Record cooldown and check bonus offspring (D-18, guard ensures no rng call at chance=0)
        lastReproducedTick.put(ra.particle.id(), tickNumber);
        if (profile.bonusOffspringChance() > 0.0
                && ThreadLocalRandom.current().nextDouble() < profile.bonusOffspringChance()) {
            Position bonusTarget = findEmptyAdjacentCell(target, claimedCells);
            if (bonusTarget != null) {
                String bonusChildId = "child-" + childIdCounter.incrementAndGet();
                Particle bonusChild = new Particle(bonusChildId, ra.particle.type(),
                        profile.childStartEnergy(), profile.maxEnergy());
                worldGrid.setEntity(bonusTarget.x(), bonusTarget.y(), bonusChild);
                claimedCells.add(bonusTarget);
            }
        }

        sendResult(ra.sessionId, tickNumber, true, "reproduce",
                "Spawned child " + childId + " at " + target);
        return true;
    }

    /**
     * Return the first empty, unclaimed neighbor of the given position, or null.
     * Used for SPORE bonus-offspring placement (D-18).
     */
    private Position findEmptyAdjacentCell(Position pos, Set<Position> claimedCells) {
        for (Position np : worldGrid.getNeighbors(pos.x(), pos.y())) {
            if (claimedCells.contains(np)) continue;
            if (worldGrid.getCell(np.x(), np.y()).isEmpty()) return np;
        }
        return null;
    }

    // ── Result delivery ───────────────────────────────────────────

    private void sendResult(String sessionId, long tickNumber, boolean success,
                             String actionType, String reason) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null || !session.isOpen()) return;

        var result = new Messages.ActionResult(tickNumber, success, actionType, reason);
        try {
            String json = objectMapper.writeValueAsString(result);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.warn("Failed to send action result to session {}: {}", sessionId, e.getMessage());
        }
    }

    // ── Composite reactive role actions ─────────────────────────

    /**
     * FEEDER consume: find adjacent nutrient, consume it, add energy to shared pool (D-15).
     */
    private void resolveFeederConsume(ResolvedCompositeAction rca,
                                      CompositeRegistry.CompositeState composite, long tickNumber) {
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

        if (nutrient == null) {
            sendResult(rca.sessionId, tickNumber, false, "consume", "No nutrient nearby");
            return;
        }

        // Consume nutrient — energy goes to shared pool (D-15), not individual energy
        // Per-type gain based on feeder's ParticleType (Phase 13)
        var feederProfile = metabolicProfile.forType(rca.member.type());
        int energyGain = feederProfile.nutrientConsumeEnergy();
        // Plan 02 D-10: starvation nutrient boost from feeder member's CURRENT energy.
        double feederIntensity = StarvationConfig.computeIntensity(
                rca.member.energy(), rca.member.maxEnergy(),
                feederProfile.starvationThreshold(), feederProfile.starvationFloor());
        if (feederIntensity > 0.0) {
            energyGain = (int) (energyGain * (1 + starvationConfig.maxNutrientBoost() * feederIntensity));
        }
        composite.addEnergy(energyGain);

        // Deplete nutrient
        Nutrient depleted = nutrient.consumed(energyGain);
        if (depleted.isDepleted()) {
            worldGrid.clearEntity(nutrientPos.x(), nutrientPos.y());
        } else {
            worldGrid.setEntity(nutrientPos.x(), nutrientPos.y(), depleted);
        }

        // Charge active drain from shared pool (graceful degradation: partial drain logged)
        int feederDrained = composite.drainEnergy(compositeConfig.feederActiveDrain());
        if (feederDrained < compositeConfig.feederActiveDrain()) {
            log.debug("Partial feeder active drain: {} of {} for composite {}",
                    feederDrained, compositeConfig.feederActiveDrain(), rca.member.compositeId());
        }

        sendResult(rca.sessionId, tickNumber, true, "consume",
                "Consumed nutrient, pool energy: " + composite.getSharedPoolEnergy());
    }

    /**
     * ATTACKER attack: find adjacent enemy entity, apply true damage (D-10, type-agnostic).
     * Damage hits target's individual energy. Active drain charged against shared pool.
     */
    private void resolveAttackerAttack(ResolvedCompositeAction rca,
                                        CompositeRegistry.CompositeState composite,
                                        Set<Position> claimedCells, long tickNumber) {
        Position pos = rca.bot.position();
        String actionType = rca.action.actionType() != null ? rca.action.actionType().toLowerCase() : "";

        // Find adjacent enemy to attack
        Direction dir = Direction.fromString(rca.action.direction());
        Position targetPos;
        if (dir != null) {
            targetPos = dir.apply(pos, worldGrid.getWidth(), worldGrid.getHeight());
        } else {
            // No direction specified — find first adjacent enemy
            targetPos = findAdjacentEnemy(pos, rca.member.compositeId());
            if (targetPos == null) {
                sendResult(rca.sessionId, tickNumber, false, "attack", "No enemy nearby");
                return;
            }
        }

        Cell targetCell = worldGrid.getCell(targetPos.x(), targetPos.y());
        Entity target = targetCell.occupant();

        if (target == null) {
            sendResult(rca.sessionId, tickNumber, false, "attack", "No target at position");
            return;
        }

        // Plan 14-05 (cycle-4 action item #3): ATTACK_PLUS_1 on the composite
        // ATTACKER member adds +1 to the base damage. LIVE method name
        // resolveAttackerAttack (NOT resolveCompositeAttack — dead name gone).
        int baseDamage = config.combatEnergyTransfer();
        if (buffRegistry.hasBuff(rca.member.id(), BuffRegistry.BuffType.ATTACK_PLUS_1)) {
            baseDamage += 1;
        }
        int damage = baseDamage;

        // Apply true damage (type-agnostic, D-10) to target's individual energy
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
            sendResult(rca.sessionId, tickNumber, false, "attack", "Invalid target");
            return;
        }

        // Plan 14-02 (cycle-4 action item #2, cycle-5 LOW): if the target was on
        // a toxic cell, splash damage fires back on the composite-member ATTACKER
        // sitting at `pos`. Direct-write path (not deferred) — must explicitly
        // Math.max(0, ...) clamp so a missing clamp can't slip through. After the
        // write we call markEnvDamageApplied so EnvPostActionReconciler @Order(25)
        // re-invokes processEnvDeaths and sweeps any lethal splash SAME TICK.
        if (environmentEngine != null) {
            int splash = environmentEngine.computeSplashDamage(targetPos);
            if (splash > 0) {
                Cell ac = worldGrid.getCell(pos.x(), pos.y());
                if (ac.occupant() instanceof Entity.CompositeMember attackerMember
                        && attackerMember.id().equals(rca.member.id())) {
                    // cycle-5 LOW: explicit Math.max(0, ...) clamp — grep-asserted.
                    int clampedEnergy = Math.max(0, attackerMember.energy() - splash);
                    worldGrid.setEntity(pos.x(), pos.y(),
                            attackerMember.withEnergy(clampedEnergy));
                    // cycle-4 action item #2: EnvPostActionReconciler finalises lethal splash.
                    environmentEngine.markEnvDamageApplied();
                }
            }
            // Plan 14-03 (D-20): attack-cure-reduction against MUTATING defender.
            // The enqueue happens during ActionResolver's @Order(20) phase;
            // EnvPostActionReconciler @Order(25) drains pending grants SAME TICK
            // via drainPostActionGrants(event.tickNumber()) (cycle-4 action item #2 +
            // cycle-6 HIGH #5a — LIVE method name resolveAttackerAttack, cycle-4 action item #3).
            String defenderId = EntityIds.entityIdOf(target);
            if (defenderId != null && environmentEngine.isInfected(defenderId)) {
                environmentEngine.reduceInfection(defenderId, environmentEngine.getAttackCureReduction(), tickNumber, targetPos);
            }
        }

        // Charge active drain from shared pool (graceful degradation: partial drain logged)
        int attackerDrained = composite.drainEnergy(compositeConfig.attackerActiveDrain());
        if (attackerDrained < compositeConfig.attackerActiveDrain()) {
            log.debug("Partial attacker active drain: {} of {} for composite {}",
                    attackerDrained, compositeConfig.attackerActiveDrain(), rca.member.compositeId());
        }

        sendResult(rca.sessionId, tickNumber, true, "attack", "Attacked target, dealt " + damage + " damage");
    }

    /**
     * Find the first adjacent enemy entity (non-member of this composite).
     */
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

    /**
     * REPRODUCER bud: spawn a solo Particle into an adjacent empty cell (D-32).
     * Energy cost from shared pool. Particle type = member's ParticleType.
     */
    private void resolveReproducerBud(ResolvedCompositeAction rca,
                                       CompositeRegistry.CompositeState composite,
                                       Set<Position> claimedCells, long tickNumber) {
        // Per-type reproduce profile for this member (Phase 13 D-17)
        var profile = metabolicProfile.forType(rca.member.type());
        int reproduceCost = profile.reproduceEnergyCost();

        if (composite.getSharedPoolEnergy() < reproduceCost) {
            sendResult(rca.sessionId, tickNumber, false, "reproduce",
                    "Not enough pool energy (need " + reproduceCost
                            + ", have " + composite.getSharedPoolEnergy() + ")");
            return;
        }

        // D-17 surplus gate: pool must remain above starvation threshold post-cost
        int poolAfterCost = composite.getSharedPoolEnergy() - reproduceCost;
        int poolStarvationFloor =
                (int) (profile.starvationThreshold() / 100.0 * composite.getMaxPoolEnergy());
        if (poolAfterCost < poolStarvationFloor) {
            sendResult(rca.sessionId, tickNumber, false, "reproduce",
                    "Would deplete pool below survival threshold (surplus " + poolAfterCost
                            + " < threshold " + poolStarvationFloor + ")");
            return;
        }

        Direction dir = Direction.fromString(rca.action.direction());
        if (dir == null) {
            sendResult(rca.sessionId, tickNumber, false, "reproduce", "Invalid direction");
            return;
        }

        Position target = dir.apply(rca.bot.position(), worldGrid.getWidth(), worldGrid.getHeight());

        if (claimedCells.contains(target)) {
            sendResult(rca.sessionId, tickNumber, false, "reproduce", "Cell claimed by another entity");
            return;
        }

        Cell targetCell = worldGrid.getCell(target.x(), target.y());
        if (targetCell.hasOccupant()) {
            sendResult(rca.sessionId, tickNumber, false, "reproduce", "Target cell is occupied");
            return;
        }

        // Spawn child Particle with member's type and per-type child energy (Phase 13)
        claimedCells.add(target);
        String childId = "child-" + childIdCounter.incrementAndGet();
        Particle child = new Particle(childId, rca.member.type(),
                profile.childStartEnergy(), profile.maxEnergy());
        worldGrid.setEntity(target.x(), target.y(), child);

        // Deduct energy from shared pool (graceful degradation: partial drain logged)
        int reproduceCostDrained = composite.drainEnergy(reproduceCost);
        if (reproduceCostDrained < reproduceCost) {
            log.debug("Partial reproduce cost drain: {} of {} for composite {}",
                    reproduceCostDrained, reproduceCost, rca.member.compositeId());
        }

        // Charge active drain
        int reproducerDrained = composite.drainEnergy(compositeConfig.reproducerActiveDrain());
        if (reproducerDrained < compositeConfig.reproducerActiveDrain()) {
            log.debug("Partial reproducer active drain: {} of {} for composite {}",
                    reproducerDrained, compositeConfig.reproducerActiveDrain(), rca.member.compositeId());
        }

        sendResult(rca.sessionId, tickNumber, true, "reproduce",
                "Budded child " + childId + " at " + target);
    }

    // ── Composite LOCOMOTOR STV voting and movement ──────────────

    /**
     * Resolve coordinated composite movement via LOCOMOTOR STV voting (D-26, D-27).
     * Collects votes per composite, resolves direction, checks speed gate, executes rigid body move.
     */
    private void resolveCompositeMovements(List<ResolvedCompositeAction> compositeActions,
                                            Set<Position> claimedCells, long tickNumber,
                                            Map<String, List<String>> rankedPreferences) {
        // Group LOCOMOTOR votes by compositeId
        Map<String, List<List<String>>> compositeVotes = new HashMap<>();
        for (ResolvedCompositeAction rca : compositeActions) {
            if (rca.member.role() == Entity.Role.LOCOMOTOR) {
                String compositeId = rca.member.compositeId();
                // Check for ranked preferences from CompositeAction, fall back to Action direction
                List<String> prefs = rankedPreferences.getOrDefault(rca.sessionId, null);
                if (prefs != null) {
                    // Cap at 3 entries, filter invalid (T-12-06, T-12-08)
                    prefs = prefs.stream().limit(3).filter(s -> Direction.fromString(s) != null).toList();
                } else {
                    prefs = extractRankedPreferences(rca.action);
                }
                compositeVotes.computeIfAbsent(compositeId, k -> new ArrayList<>()).add(prefs);
            }
        }

        // Increment ticks-since-move for all tracked composites
        // Note: newly formed composites are NOT pre-seeded — getOrDefault(id, moveInterval)
        // provides the correct default, allowing first-tick movement then resetting to 0.
        for (String compositeId : compositeTicksSinceMove.keySet()) {
            compositeTicksSinceMove.merge(compositeId, 1, Integer::sum);
        }

        // Process each composite with votes
        for (var entry : compositeVotes.entrySet()) {
            String compositeId = entry.getKey();
            List<List<String>> votes = entry.getValue();

            var compositeOpt = compositeRegistry.getComposite(compositeId);
            if (compositeOpt.isEmpty()) continue;
            var composite = compositeOpt.get();

            // Resolve direction from votes
            Direction direction = resolveLocomotorVote(votes);
            if (direction == null) continue; // No valid votes

            // Check speed gate (D-23)
            int locomotorCount = countLocomotors(composite);
            int colonySize = composite.getMemberCount();
            double speed = (double) locomotorCount / colonySize * compositeConfig.speedConstant();
            int moveInterval = speed >= 1.0 ? 1 : (int) Math.ceil(1.0 / speed);
            // Plan 14-05 cycle-6 MEDIUM #10: MOVEMENT_PLUS_1 on ANY LOCOMOTOR
            // member reduces the effective move interval by 1 (floored at 1).
            // Non-LOCOMOTOR buffed members do NOT trigger reduced cadence —
            // the buff only affects cadence when it lives on a role that moves.
            // Baseline behavior for unbuffed composites is UNCHANGED.
            int effectiveInterval = hasAnyLocomotorMovementBuff(composite)
                    ? Math.max(1, moveInterval - 1)
                    : moveInterval;
            int ticksSince = compositeTicksSinceMove.getOrDefault(compositeId, effectiveInterval);
            if (ticksSince < effectiveInterval) {
                continue; // Skip movement this tick
            }

            // Execute rigid body movement
            boolean moved = executeCompositeMovement(compositeId, direction, composite, claimedCells);
            if (moved) {
                compositeTicksSinceMove.put(compositeId, 0);
                // Charge LOCOMOTOR active drain (graceful degradation: partial drain logged)
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

        // Phase 13: prune stale reproduce cooldown entries — mirror the
        // compositeTicksSinceMove pattern to prevent unbounded growth as
        // entities die. Active entity ids are taken from BotRegistry.
        Set<String> activeEntityIds = new HashSet<>();
        for (var bot : botRegistry.getAllBots()) {
            activeEntityIds.add(bot.entityId());
        }
        lastReproducedTick.keySet().retainAll(activeEntityIds);
    }

    /**
     * Resolve LOCOMOTOR votes using first-preference plurality with random tie-break.
     * Simplified STV per research recommendation — full Droop quota transfer is over-engineered.
     */
    Direction resolveLocomotorVote(List<List<String>> rankedVotes) {
        Map<Direction, Integer> counts = new EnumMap<>(Direction.class);
        for (List<String> prefs : rankedVotes) {
            if (prefs != null && !prefs.isEmpty()) {
                Direction d = Direction.fromString(prefs.get(0));
                if (d != null) counts.merge(d, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) return null;

        int max = Collections.max(counts.values());
        List<Direction> winners = counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
        return winners.get(ThreadLocalRandom.current().nextInt(winners.size()));
    }

    /**
     * Execute rigid body movement for a composite — all members shift in the same direction (D-24).
     * Blocked if ANY member's target cell is occupied by a non-member entity.
     */
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

        // Calculate all target positions
        List<Position> targetPositions = currentPositions.stream()
                .map(p -> dir.apply(p, worldGrid.getWidth(), worldGrid.getHeight()))
                .toList();

        // Check ALL targets unoccupied and unclaimed
        Set<Position> currentPosSet = new HashSet<>(currentPositions);
        for (Position target : targetPositions) {
            if (claimedCells.contains(target)) return false;
            if (!currentPosSet.contains(target)) {
                // Target is not a position already occupied by this composite
                Cell tc = worldGrid.getCell(target.x(), target.y());
                if (tc.hasOccupant()) {
                    return false;
                }
            }
        }

        // Claim all targets
        claimedCells.addAll(targetPositions);

        // Execute: clear source cells, place members at target cells
        for (Position pos : currentPositions) {
            worldGrid.clearEntity(pos.x(), pos.y());
        }
        Map<String, Position> newPositions = new HashMap<>();
        for (int i = 0; i < targetPositions.size(); i++) {
            Position target = targetPositions.get(i);
            Entity.CompositeMember member = members.get(i);
            worldGrid.setEntity(target.x(), target.y(), member);

            // Update BotRegistry
            botRegistry.getSessionForEntity(member.id()).ifPresent(sid ->
                    botRegistry.updatePosition(sid, target));
            newPositions.put(member.id(), target);
        }

        // Update CompositeRegistry positions
        compositeRegistry.updateMemberPositions(compositeId, newPositions);

        return true;
    }

    /**
     * Extract ranked preferences from an action. Handles both Action (no prefs)
     * and CompositeAction (has rankedPreferences). Caps at 3 entries (T-12-06, T-12-08).
     */
    List<String> extractRankedPreferences(Messages.Action action) {
        // Regular Action — use direction as sole preference
        if (action.direction() != null) {
            return List.of(action.direction());
        }
        return List.of();
    }

    /**
     * Extract ranked preferences from a CompositeAction. Caps at 3 entries.
     */
    List<String> extractRankedPreferences(Messages.CompositeAction action) {
        List<String> prefs = action.rankedPreferences();
        if (prefs == null || prefs.isEmpty()) {
            if (action.direction() != null) {
                return List.of(action.direction());
            }
            return List.of();
        }
        // Cap at 3 entries (T-12-06, T-12-08), filter invalid directions
        return prefs.stream()
                .limit(3)
                .filter(s -> Direction.fromString(s) != null)
                .toList();
    }

    /**
     * Count LOCOMOTOR members in a composite by scanning the grid.
     */
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
            Messages.Action action
    ) {}

    private record ResolvedCompositeAction(
            String sessionId,
            BotRegistry.BotState bot,
            Entity.CompositeMember member,
            Messages.Action action
    ) {}
}
