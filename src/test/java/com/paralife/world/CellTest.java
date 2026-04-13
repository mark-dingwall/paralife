package com.paralife.world;

import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Rock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellTest {

    @Test
    void emptyCellHasNoOccupant() {
        assertThat(Cell.EMPTY.isEmpty()).isTrue();
        assertThat(Cell.EMPTY.hasOccupant()).isFalse();
        assertThat(Cell.EMPTY.flags()).isEqualTo(Cell.FLAG_NONE);
        assertThat(Cell.EMPTY.nutrientLevel()).isEqualTo(0);
    }

    @Test
    void withOccupant() {
        Particle p = Particle.spawn("p1", ParticleType.CATALYST);
        Cell cell = Cell.EMPTY.withOccupant(p);
        assertThat(cell.hasOccupant()).isTrue();
        assertThat(cell.occupant()).isEqualTo(p);
    }

    @Test
    void cleared() {
        Particle p = Particle.spawn("p1", ParticleType.SPORE);
        Cell cell = Cell.EMPTY.withOccupant(p).cleared();
        assertThat(cell.isEmpty()).isTrue();
    }

    @Test
    void flagOperations() {
        int FLAG_FIRE = 1;
        int FLAG_DISEASE = 2;

        Cell cell = Cell.EMPTY.withAddedFlag(FLAG_FIRE);
        assertThat(cell.hasFlag(FLAG_FIRE)).isTrue();
        assertThat(cell.hasFlag(FLAG_DISEASE)).isFalse();

        cell = cell.withAddedFlag(FLAG_DISEASE);
        assertThat(cell.hasFlag(FLAG_FIRE)).isTrue();
        assertThat(cell.hasFlag(FLAG_DISEASE)).isTrue();

        cell = cell.withRemovedFlag(FLAG_FIRE);
        assertThat(cell.hasFlag(FLAG_FIRE)).isFalse();
        assertThat(cell.hasFlag(FLAG_DISEASE)).isTrue();
    }

    @Test
    void withNutrientLevel() {
        Cell cell = Cell.EMPTY.withNutrientLevel(5);
        assertThat(cell.nutrientLevel()).isEqualTo(5);
    }

    @Test
    void negativeNutrientLevelRejected() {
        assertThatThrownBy(() -> Cell.EMPTY.withNutrientLevel(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void immutability() {
        Cell original = Cell.EMPTY;
        Cell modified = original.withOccupant(new Rock("r1")).withNutrientLevel(3);
        // Original unchanged
        assertThat(original.isEmpty()).isTrue();
        assertThat(original.nutrientLevel()).isEqualTo(0);
        // Modified has changes
        assertThat(modified.occupant()).isInstanceOf(Rock.class);
        assertThat(modified.nutrientLevel()).isEqualTo(3);
    }
}
