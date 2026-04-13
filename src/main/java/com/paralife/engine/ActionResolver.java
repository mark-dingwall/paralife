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
    private final AtomicLong childIdCounter = new AtomicLong(0);

    /**
     * Pending action: sessionId → action. Only the last action per session per tick is kept.
     * Uses AtomicReference swap for atomic drain — see {@link #onTick}.
     */
    private final AtomicReference<ConcurrentHashMap<String, Messages.Action>> pendingActions =
            new AtomicReference<>(new ConcurrentHashMap<>());

    public ActionResolver(WorldGrid worldGrid, BotRegistry botRegistry,
                           SessionRegistry sessionRegistry, SimulationConfig config,
                           ObjectMapper objectMapper) {
        this.worldGrid = worldGrid;
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * Queue an action from a bot. Replaces any previous action for the same session this tick.
     */
    public void queueAction(String sessionId, Messages.Action action) {
        pendingActions.get().put(sessionId, action);
        log.debug("Action queued: session={} type={} dir={}", sessionId,
                action.actionType(), action.direction());
    }

    @EventListener
    @Order(20) // After SimulationEngine(10), before PerceptionBroadcaster(50)
    public void onTick(TickEvent event) {
        // Atomically swap in a fresh map — no window for lost actions
        var actions = pendingActions.getAndSet(new ConcurrentHashMap<>());

        if (actions.isEmpty()) return;

        resolveActions(event.tickNumber(), actions);
    }

    /**
     * Resolve all queued actions for a tick. Public for testing.
     */
    void resolveActions(long tickNumber, Map<String, Messages.Action> actions) {
        int moveCount = 0, consumeCount = 0, reproduceCount = 0, restCount = 0;
        int conflicts = 0;

        // Phase 1: Parse and validate actions, build resolution list
        List<ResolvedAction> resolvedList = new ArrayList<>();
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
            if (!(cell.occupant() instanceof Particle particle) || !particle.id().equals(bot.entityId())) {
                sendResult(sessionId, tickNumber, false, action.actionType(), "Entity no longer alive");
                botRegistry.unregisterBySession(sessionId);
                continue;
            }

            resolvedList.add(new ResolvedAction(sessionId, bot, particle, action));
        }

        // Phase 2: Shuffle for fairness then resolve
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

    // ── Internal ──────────────────────────────────────────────────

    private record ResolvedAction(
            String sessionId,
            BotRegistry.BotState bot,
            Particle particle,
            Messages.Action action
    ) {}
}
