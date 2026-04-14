package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
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

    /** Energy cost to reproduce. Parent must have at least this much energy. */
    public static final int REPRODUCE_ENERGY_COST = 30;
    /** Starting energy for a child entity produced by reproduction. */
    public static final int CHILD_START_ENERGY = 20;

    private final WorldGrid worldGrid;
    private final BotRegistry botRegistry;
    private final SessionRegistry sessionRegistry;
    private final SimulationConfig config;
    private final ObjectMapper objectMapper;
    private final CompositeRegistry compositeRegistry;
    private final CompositeConfig compositeConfig;
    private final AtomicLong childIdCounter = new AtomicLong(0);

    /** Tracks ticks since last movement per composite, for speed gating (D-23). */
    private final ConcurrentHashMap<String, Integer> compositeTicksSinceMove = new ConcurrentHashMap<>();

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

    public ActionResolver(WorldGrid worldGrid, BotRegistry botRegistry,
                           SessionRegistry sessionRegistry, SimulationConfig config,
                           ObjectMapper objectMapper,
                           CompositeRegistry compositeRegistry, CompositeConfig compositeConfig) {
        this.worldGrid = worldGrid;
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.config = config;
        this.objectMapper = objectMapper;
        this.compositeRegistry = compositeRegistry;
        this.compositeConfig = compositeConfig;
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

        Position target = dir.apply(ra.bot.position(), worldGrid.getWidth(), worldGrid.getHeight());

        // Check if cell is already claimed this tick
        if (claimedCells.contains(target)) {
            sendResult(ra.sessionId, tickNumber, false, "move", "Cell claimed by another entity");
            return false;
        }

        // Check target cell
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

        // If target has a nutrient, the particle replaces it (auto-consume on move)
        worldGrid.setEntity(target.x(), target.y(), ra.particle);
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

        // Consume the nutrient
        int energyGain = config.nutrientConsumeEnergy();
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
        if (ra.particle.energy() < REPRODUCE_ENERGY_COST) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce",
                    "Not enough energy (need " + REPRODUCE_ENERGY_COST + ", have " + ra.particle.energy() + ")");
            return false;
        }

        Direction dir = Direction.fromString(ra.action.direction());
        if (dir == null) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce", "Invalid direction");
            return false;
        }

        Position target = dir.apply(ra.bot.position(), worldGrid.getWidth(), worldGrid.getHeight());

        if (claimedCells.contains(target)) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce", "Cell claimed by another entity");
            return false;
        }

        Cell targetCell = worldGrid.getCell(target.x(), target.y());
        if (targetCell.hasOccupant()) {
            sendResult(ra.sessionId, tickNumber, false, "reproduce", "Target cell is occupied");
            return false;
        }

        // Spawn child
        claimedCells.add(target);
        String childId = "child-" + childIdCounter.incrementAndGet();
        Particle child = new Particle(childId, ra.particle.type(), CHILD_START_ENERGY, ra.particle.maxEnergy());
        worldGrid.setEntity(target.x(), target.y(), child);

        // Deduct parent energy
        Particle updatedParent = ra.particle.withEnergy(ra.particle.energy() - REPRODUCE_ENERGY_COST);
        worldGrid.setEntity(ra.bot.position().x(), ra.bot.position().y(), updatedParent);

        sendResult(ra.sessionId, tickNumber, true, "reproduce",
                "Spawned child " + childId + " at " + target);
        return true;
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
        int energyGain = config.nutrientConsumeEnergy();
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

        int damage = config.combatEnergyTransfer();

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
        if (composite.getSharedPoolEnergy() < REPRODUCE_ENERGY_COST) {
            sendResult(rca.sessionId, tickNumber, false, "reproduce",
                    "Not enough pool energy (need " + REPRODUCE_ENERGY_COST
                            + ", have " + composite.getSharedPoolEnergy() + ")");
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

        // Spawn child Particle with member's type (D-32)
        claimedCells.add(target);
        String childId = "child-" + childIdCounter.incrementAndGet();
        Particle child = new Particle(childId, rca.member.type(), CHILD_START_ENERGY, rca.member.maxEnergy());
        worldGrid.setEntity(target.x(), target.y(), child);

        // Deduct energy from shared pool (graceful degradation: partial drain logged)
        int reproduceCostDrained = composite.drainEnergy(REPRODUCE_ENERGY_COST);
        if (reproduceCostDrained < REPRODUCE_ENERGY_COST) {
            log.debug("Partial reproduce cost drain: {} of {} for composite {}",
                    reproduceCostDrained, REPRODUCE_ENERGY_COST, rca.member.compositeId());
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

        // Initialize tracking for newly formed composites — use MAX_VALUE so first tick passes speed gate,
        // then resets to 0 on successful movement (WR-02: prevent stale default, but allow first-tick move)
        for (var composite : compositeRegistry.getAll()) {
            compositeTicksSinceMove.putIfAbsent(composite.getCompositeId(), Integer.MAX_VALUE);
        }

        // Increment ticks-since-move for all tracked composites
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
            int ticksSince = compositeTicksSinceMove.getOrDefault(compositeId, moveInterval);
            if (ticksSince < moveInterval) {
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
