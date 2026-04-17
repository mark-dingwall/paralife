package com.paralife.bot;

import com.paralife.engine.Direction;
import com.paralife.websocket.Messages;
import com.paralife.websocket.Messages.CellView;
import com.paralife.websocket.Messages.EntityState;
import com.paralife.websocket.Messages.Perception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicBrainTest {

    private HeuristicBrain brain;

    @BeforeEach
    void setUp() {
        brain = new HeuristicBrain();
    }

    /**
     * Build a 5x5 perception grid, all empty by default.
     */
    private List<List<CellView>> emptyNeighbourhood() {
        List<List<CellView>> grid = new ArrayList<>();
        for (int row = 0; row < 5; row++) {
            List<CellView> rowList = new ArrayList<>();
            for (int col = 0; col < 5; col++) {
                rowList.add(new CellView(null, null, 0));
            }
            grid.add(rowList);
        }
        return grid;
    }

    private Perception makePerception(String type, int energy, List<List<CellView>> neighbourhood) {
        var self = new EntityState("e1", type, energy, 100, 5, 5);
        return new Perception(1, self, neighbourhood, 2);
    }

    @Test
    void randomWalkWhenAllEmpty() {
        var perception = makePerception("CATALYST", 50, emptyNeighbourhood());
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("move");
        assertThat(decision.direction()).isNotNull();
    }

    @Test
    void fleeFromAdjacentPredator() {
        // CATALYST's predator is MEMBRANE
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, new CellView("MEMBRANE", "pred1", 0)); // East of centre

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        // Should flee — move action away from predator
        assertThat(decision.actionType()).isEqualTo("move");
        // Should not move east (toward predator)
        assertThat(decision.direction()).isNotEqualTo("E");
    }

    @Test
    void chaseAdjacentPrey() {
        // CATALYST's prey is SPORE
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, new CellView("SPORE", "prey1", 0)); // East of centre

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("move");
        assertThat(decision.direction()).isEqualTo("E");
    }

    @Test
    void consumeAdjacentNutrient() {
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, new CellView("NUTRIENT", "n1", 0)); // East of centre

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("consume");
    }

    @Test
    void reproduceWhenEnergyHigh() {
        var perception = makePerception("CATALYST", 80, emptyNeighbourhood());
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("reproduce");
        assertThat(decision.direction()).isNotNull();
    }

    @Test
    void dontReproduceWhenEnergyLow() {
        var perception = makePerception("CATALYST", 30, emptyNeighbourhood());
        var decision = brain.decide(perception);

        // Should random walk instead
        assertThat(decision.actionType()).isNotEqualTo("reproduce");
    }

    @Test
    void fleePrioritizedOverChase() {
        // Both predator and prey adjacent — should flee
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, new CellView("MEMBRANE", "pred1", 0)); // East = predator
        grid.get(2).set(1, new CellView("SPORE", "prey1", 0));    // West = prey

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("move");
        // Should flee from predator (east), not chase prey
        assertThat(decision.direction()).isNotEqualTo("E");
    }

    @Test
    void chasePrioritizedOverConsume() {
        // Both prey and nutrient adjacent — should chase
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, new CellView("SPORE", "prey1", 0));    // East = prey
        grid.get(2).set(1, new CellView("NUTRIENT", "n1", 0));    // West = nutrient

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("move");
        assertThat(decision.direction()).isEqualTo("E");
    }

    @Test
    void restWhenSurroundedByRocks() {
        var grid = emptyNeighbourhood();
        // Fill all adjacent cells with rocks
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                grid.get(2 + dy).set(2 + dx, new CellView("ROCK", "r", 0));
            }
        }

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("rest");
    }

    @Test
    void closestDirectionMapping() {
        assertThat(HeuristicBrain.closestDirection(1, 0)).isEqualTo(Direction.E);
        assertThat(HeuristicBrain.closestDirection(-1, 0)).isEqualTo(Direction.W);
        assertThat(HeuristicBrain.closestDirection(0, -1)).isEqualTo(Direction.N);
        assertThat(HeuristicBrain.closestDirection(0, 1)).isEqualTo(Direction.S);
        assertThat(HeuristicBrain.closestDirection(1, 1)).isEqualTo(Direction.SE);
        assertThat(HeuristicBrain.closestDirection(-1, -1)).isEqualTo(Direction.NW);
        assertThat(HeuristicBrain.closestDirection(0, 0)).isNull();
    }

    @Test
    void moveTowardDistantNutrient() {
        var grid = emptyNeighbourhood();
        // Nutrient at distance 2, NE corner
        grid.get(0).set(4, new CellView("NUTRIENT", "n1", 0));

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        // Should move toward nutrient
        assertThat(decision.actionType()).isEqualTo("move");
        assertThat(decision.direction()).isEqualTo("NE");
    }

    @Test
    void membraneFleesCatalyst() {
        // MEMBRANE's predator is SPORE (since SPORE beats MEMBRANE)
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, new CellView("SPORE", "pred1", 0)); // East = predator for MEMBRANE

        var perception = makePerception("MEMBRANE", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("move");
        assertThat(decision.direction()).isNotEqualTo("E");
    }

    // ════════════════════════════════════════════════════════════════
    // Plan 14-05 Task 3: observable-only env reactions (D-43)
    // ════════════════════════════════════════════════════════════════

    /** Build a CellView with explicit flags/cellStatus/entityStatus. */
    private CellView cellWithStatus(String type, String id, int flags,
                                     byte cellStatus, byte entityStatus) {
        return new CellView(type, id, 0, flags, cellStatus, entityStatus);
    }

    @Test
    void starvingPreyPreferredOverNonStarvingPrey() {
        // CATALYST's prey is SPORE. Two SPORE prey at distance 1 — one
        // STARVING (flags bit 1) and one healthy. Brain should pick the
        // STARVING one (priority +2).
        var grid = emptyNeighbourhood();
        // East = starving prey
        grid.get(2).set(3, cellWithStatus("SPORE", "starving", com.paralife.world.Cell.FLAG_STARVING,
                (byte) 0, (byte) 0));
        // West = healthy prey
        grid.get(2).set(1, new CellView("SPORE", "healthy", 0));

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("move");
        assertThat(decision.direction())
                .as("STARVING prey gets +2 priority — prefer east over west")
                .isEqualTo("E");
    }

    @Test
    void lowEnergyBotAvoidsToxicMoveTarget() {
        // Low-energy bot (energy < 30% of maxEnergy=100 → < 30) should NOT
        // move into a TOXIC empty cell (cellStatus bit 0 set).
        var grid = emptyNeighbourhood();
        // East = TOXIC empty cell. Only adjacent cell not blocked → forced
        // choice would be east, but TOXIC avoidance removes it from the pool.
        grid.get(2).set(3, cellWithStatus(null, null, 0,
                HeuristicBrain.CELL_STATUS_TOXIN_PRESENT, (byte) 0));
        // Fill other adjacent cells with rocks so east is the only empty cell.
        grid.get(1).set(2, new CellView("ROCK", "r1", 0)); // N
        grid.get(3).set(2, new CellView("ROCK", "r2", 0)); // S
        grid.get(2).set(1, new CellView("ROCK", "r3", 0)); // W
        grid.get(1).set(3, new CellView("ROCK", "r4", 0)); // NE
        grid.get(3).set(3, new CellView("ROCK", "r5", 0)); // SE
        grid.get(1).set(1, new CellView("ROCK", "r6", 0)); // NW
        grid.get(3).set(1, new CellView("ROCK", "r7", 0)); // SW

        var perception = makePerception("CATALYST", 20, grid);  // energy 20 < 30
        var decision = brain.decide(perception);

        // No non-TOXIC empty cells → brain rests rather than stepping onto toxic.
        assertThat(decision.actionType())
                .as("low-energy bot avoids TOXIC cell — no other options → rest")
                .isEqualTo("rest");
    }

    @Test
    void highEnergyBotAcceptsToxicMoveTarget() {
        // High-energy bot (energy >= 30% of maxEnergy) CAN move into a TOXIC
        // empty cell — the splash risk is acceptable.
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, cellWithStatus(null, null, 0,
                HeuristicBrain.CELL_STATUS_TOXIN_PRESENT, (byte) 0));
        // Fill all other adjacent cells with rocks so the toxic empty is the
        // only viable move target.
        grid.get(1).set(2, new CellView("ROCK", "r1", 0));
        grid.get(3).set(2, new CellView("ROCK", "r2", 0));
        grid.get(2).set(1, new CellView("ROCK", "r3", 0));
        grid.get(1).set(3, new CellView("ROCK", "r4", 0));
        grid.get(3).set(3, new CellView("ROCK", "r5", 0));
        grid.get(1).set(1, new CellView("ROCK", "r6", 0));
        grid.get(3).set(1, new CellView("ROCK", "r7", 0));

        var perception = makePerception("CATALYST", 90, grid);  // energy 90 >> 30
        var decision = brain.decide(perception);

        // High-energy → TOXIC cell is a valid move target (no reproduce because
        // no non-TOXIC empty adjacent to spawn child, and no consume because
        // no nutrients). Expect reproduce since energy >= 70 BUT no non-TOXIC
        // empty neighbour. Falls through to random walk — ends up moving E.
        // If brain chooses to rest (no valid option), still acceptable. Only
        // fail if it refuses to MOVE east when only empty cell is east.
        //
        // Actually with energy 90, reproduce triggers on any adjacent empty.
        // But our only empty is TOXIC — reproduce rule currently uses emptyCells
        // (which INCLUDES the TOXIC cell for high-energy bots per our logic).
        // If the brain reproduces east that's still "used the TOXIC cell" → OK.
        assertThat(decision.actionType())
                .as("high-energy bot accepts TOXIC cell as a valid target")
                .isIn("move", "reproduce");
        assertThat(decision.direction())
                .as("high-energy bot uses the TOXIC east cell")
                .isEqualTo("E");
    }

    @Test
    void buffedPreyDeprioritizedVsCleanPrey() {
        // Two prey at same distance — one BUFFED (entityStatus bit 3),
        // one clean. Brain picks the clean one (priority +0 vs -1).
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, cellWithStatus("SPORE", "buffed", 0,
                (byte) 0, HeuristicBrain.ENTITY_STATUS_BUFFED));
        grid.get(2).set(1, new CellView("SPORE", "clean", 0));

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("move");
        assertThat(decision.direction())
                .as("BUFFED prey deprioritized (-1), brain picks clean west prey")
                .isEqualTo("W");
    }

    @Test
    void mutatingPreyDeprioritizedVsCleanPrey() {
        // Two prey at same distance — one MUTATING, one clean. Brain picks clean.
        var grid = emptyNeighbourhood();
        grid.get(2).set(3, cellWithStatus("SPORE", "sick", 0,
                (byte) 0, HeuristicBrain.ENTITY_STATUS_MUTATING));
        grid.get(2).set(1, new CellView("SPORE", "clean", 0));

        var perception = makePerception("CATALYST", 50, grid);
        var decision = brain.decide(perception);

        assertThat(decision.actionType()).isEqualTo("move");
        assertThat(decision.direction())
                .as("MUTATING prey deprioritized (-1), brain picks clean west prey")
                .isEqualTo("W");
    }
}
