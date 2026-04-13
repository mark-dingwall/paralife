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
}
