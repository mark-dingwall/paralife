package com.paralife.bot;

import com.paralife.engine.Direction;
import com.paralife.websocket.Messages.CellView;
import com.paralife.websocket.Messages.Perception;
import com.paralife.world.Cell;
import com.paralife.world.Entity.ParticleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple priority-based heuristic brain for bot decision-making.
 *
 * <p>Priority order:
 * <ol>
 *   <li>Flee if predator is adjacent</li>
 *   <li>Attack if prey is adjacent (move toward it)</li>
 *   <li>Consume if nutrient is adjacent</li>
 *   <li>Reproduce if energy is high enough</li>
 *   <li>Random walk</li>
 * </ol>
 *
 * <p><b>Plan 14-05 Task 3 — observable-only env reactions (D-43):</b>
 * decisions read {@code CellView.cellStatus} + {@code CellView.entityStatus}
 * and adjust priorities using ONLY observable information:
 * <ul>
 *   <li>STARVING prey (flags bit 1): +2 prey priority — easy target</li>
 *   <li>TOXIC cell (cellStatus bit 0): move targets there skipped when
 *       self energy is low (splash damage risk)</li>
 *   <li>MUTATING prey (entityStatus bit 2): -1 priority — gamble avoidance</li>
 *   <li>BUFFED prey (entityStatus bit 3): -1 priority — cautious</li>
 * </ul>
 * The brain does NOT read any infection-countdown fields or server-internal
 * cure-reduction values — only fields exposed by the perception protocol.
 * Zero-trust: any server-side state not on CellView/EntityState is invisible.
 */
public class HeuristicBrain {

    private static final Logger log = LoggerFactory.getLogger(HeuristicBrain.class);

    /** Energy threshold above which the bot will try to reproduce. */
    public static final int REPRODUCE_THRESHOLD = 70;

    /** Plan 14-05: bit 0 in {@code entityStatus} — entity currently on a toxic cell. */
    static final byte ENTITY_STATUS_TOXIC = 0x01;
    /** Plan 14-05: bit 2 in {@code entityStatus} — entity has an active infection. */
    static final byte ENTITY_STATUS_MUTATING = 0x04;
    /** Plan 14-05: bit 3 in {@code entityStatus} — entity carries an active survivor buff. */
    static final byte ENTITY_STATUS_BUFFED = 0x08;
    /** Plan 14-05: bit 0 in {@code cellStatus} — cell's toxin intensity above threshold. */
    static final byte CELL_STATUS_TOXIN_PRESENT = 0x01;

    /**
     * Fraction of maxEnergy below which the bot treats TOXIC cells as no-go.
     * Keeping this public-static-final so tests can lock the contract without
     * reflection.
     */
    public static final double TOXIC_AVOIDANCE_ENERGY_FRACTION = 0.30;

    /**
     * A decision: action type + optional direction.
     */
    public record Decision(String actionType, String direction) {
        public static Decision move(Direction dir) { return new Decision("move", dir.name()); }
        public static Decision consume() { return new Decision("consume", null); }
        public static Decision reproduce(Direction dir) { return new Decision("reproduce", dir.name()); }
        public static Decision rest() { return new Decision("rest", null); }
    }

    /**
     * Decide what to do based on the current perception.
     *
     * <p>Plan 14-05 Task 3 observable-only env reactions:
     * <ul>
     *   <li>Prey priority = -distance + 2 if STARVING − 1 if MUTATING − 1 if BUFFED</li>
     *   <li>Move targets avoid TOXIC cells when self energy is below
     *       {@link #TOXIC_AVOIDANCE_ENERGY_FRACTION} of maxEnergy</li>
     * </ul>
     */
    public Decision decide(Perception perception) {
        var self = perception.self();
        ParticleType myType;
        try {
            myType = ParticleType.valueOf(self.particleType());
        } catch (IllegalArgumentException e) {
            return Decision.rest();
        }

        int radius = perception.radius();
        int center = radius; // Centre of the neighbourhood grid
        var neighbourhood = perception.neighbourhood();

        // Plan 14-05: only move to TOXIC cells when we have energy to spare.
        boolean lowEnergy = self.maxEnergy() > 0
                && self.energy() < TOXIC_AVOIDANCE_ENERGY_FRACTION * self.maxEnergy();

        // Scan neighbourhood for threats, prey, and nutrients
        List<DirectionInfo> predators = new ArrayList<>();
        List<ScoredTarget> prey = new ArrayList<>();
        List<DirectionInfo> nutrients = new ArrayList<>();
        List<DirectionInfo> emptyCells = new ArrayList<>();

        ParticleType preyType = myType.prey();
        ParticleType predatorType = preyType.predator() == myType ? myType.predator() : predatorOf(myType);

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue; // Skip self

                int row = center + dy;
                int col = center + dx;
                CellView cell = neighbourhood.get(row).get(col);
                Direction dir = closestDirection(dx, dy);
                if (dir == null) continue;

                int dist = Math.abs(dx) + Math.abs(dy);

                // Plan 14-05: TOXIC cell observable via cellStatus bit 0.
                // Skip the cell as a move/consume/reproduce target when low on
                // energy — splash damage risk outweighs any gain. Still
                // recognise prey/predator occupants for the flee/chase logic
                // (STRIDE-aware: we don't get blinded — we just don't STEP
                // into the toxic cell).
                boolean toxicCell = (cell.cellStatus() & CELL_STATUS_TOXIN_PRESENT) != 0;

                if (cell.occupantType() == null) {
                    if (!(lowEnergy && toxicCell)) {
                        emptyCells.add(new DirectionInfo(dir, dist));
                    }
                } else if (cell.occupantType().equals("NUTRIENT")) {
                    if (!(lowEnergy && toxicCell)) {
                        nutrients.add(new DirectionInfo(dir, dist));
                    }
                } else if (cell.occupantType().equals(preyType.name())) {
                    int priority = -dist;
                    if ((cell.flags() & Cell.FLAG_STARVING) != 0) priority += 2;
                    if ((cell.entityStatus() & ENTITY_STATUS_MUTATING) != 0) priority -= 1;
                    if ((cell.entityStatus() & ENTITY_STATUS_BUFFED) != 0) priority -= 1;
                    prey.add(new ScoredTarget(dir, dist, priority));
                } else if (cell.occupantType().equals(predatorType.name())) {
                    predators.add(new DirectionInfo(dir, dist));
                }
            }
        }

        // Priority 1: Flee from adjacent predators
        var adjacentPredators = predators.stream().filter(d -> d.distance <= 1).toList();
        if (!adjacentPredators.isEmpty()) {
            Direction fleeDir = fleeFrom(adjacentPredators, emptyCells);
            if (fleeDir != null) {
                log.debug("Bot {} fleeing {}", self.entityId(), fleeDir);
                return Decision.move(fleeDir);
            }
        }

        // Priority 2: Move toward best adjacent prey (observable-priority-weighted)
        var adjacentPrey = prey.stream().filter(d -> d.distance <= 2).toList();
        if (!adjacentPrey.isEmpty()) {
            Direction chaseDir = adjacentPrey.stream()
                    .max((a, b) -> Integer.compare(a.priority, b.priority))
                    .map(t -> t.direction).orElse(null);
            if (chaseDir != null) {
                log.debug("Bot {} chasing prey {}", self.entityId(), chaseDir);
                return Decision.move(chaseDir);
            }
        }

        // Priority 3: Consume adjacent nutrient
        var adjacentNutrients = nutrients.stream().filter(d -> d.distance <= 1).toList();
        if (!adjacentNutrients.isEmpty()) {
            log.debug("Bot {} consuming nutrient", self.entityId());
            return Decision.consume();
        }

        // Priority 4: Reproduce if energy is high
        if (self.energy() >= REPRODUCE_THRESHOLD) {
            var emptyAdjacent = emptyCells.stream().filter(d -> d.distance <= 1).toList();
            if (!emptyAdjacent.isEmpty()) {
                Direction repDir = emptyAdjacent.get(ThreadLocalRandom.current().nextInt(emptyAdjacent.size())).direction;
                log.debug("Bot {} reproducing {}", self.entityId(), repDir);
                return Decision.reproduce(repDir);
            }
        }

        // Priority 5: Move toward nearby nutrients (not adjacent)
        if (!nutrients.isEmpty()) {
            Direction nutrientDir = nutrients.stream()
                    .min((a, b) -> Integer.compare(a.distance, b.distance))
                    .map(d -> d.direction).orElse(null);
            if (nutrientDir != null) {
                return Decision.move(nutrientDir);
            }
        }

        // Priority 6: Random walk
        if (!emptyCells.isEmpty()) {
            var adjacent = emptyCells.stream().filter(d -> d.distance <= 1).toList();
            if (!adjacent.isEmpty()) {
                Direction randomDir = adjacent.get(ThreadLocalRandom.current().nextInt(adjacent.size())).direction;
                return Decision.move(randomDir);
            }
        }

        return Decision.rest();
    }

    /**
     * Choose a direction to flee from predators toward an empty cell.
     */
    private Direction fleeFrom(List<DirectionInfo> predators, List<DirectionInfo> emptyCells) {
        if (emptyCells.isEmpty()) return null;

        // Find the direction most opposite to the average predator direction
        var adjacent = emptyCells.stream().filter(d -> d.distance <= 1).toList();
        if (adjacent.isEmpty()) return null;

        // Simple: pick an adjacent empty cell that isn't in the predator directions
        var predatorDirs = predators.stream().map(d -> d.direction).toList();
        for (var cell : adjacent) {
            if (!predatorDirs.contains(cell.direction)) {
                return cell.direction;
            }
        }
        // If all adjacent cells have predators, pick any
        return adjacent.get(ThreadLocalRandom.current().nextInt(adjacent.size())).direction;
    }

    /**
     * Map a (dx, dy) offset to the closest compass direction.
     */
    static Direction closestDirection(int dx, int dy) {
        if (dx == 0 && dy == 0) return null;
        // Normalize to unit direction
        int ndx = Integer.compare(dx, 0);
        int ndy = Integer.compare(dy, 0);

        for (Direction d : Direction.values()) {
            if (d.dx() == ndx && d.dy() == ndy) {
                return d;
            }
        }
        return null;
    }

    private ParticleType predatorOf(ParticleType type) {
        return type.predator();
    }

    private record DirectionInfo(Direction direction, int distance) {}

    /**
     * Plan 14-05 Task 3: prey target with observable-only priority score
     * (higher = more attractive). Score = -distance + STARVING bonus − MUTATING
     * penalty − BUFFED penalty. Selected via max() in the chase branch.
     */
    private record ScoredTarget(Direction direction, int distance, int priority) {}
}
