package com.paralife.websocket;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.LiveEntityRegistry;
import com.paralife.engine.TickEvent;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 19.5 H3 regression test — register-first ordering at
 * {@code WorldWebSocketHandler.handleRegister} must never produce the
 * "grid has it, registry doesn't" transient that pre-Phase-19 grid-walk would
 * have caught but Plan-04 entity-list iteration silently skips.
 *
 * <p><b>Bug</b>: pre-H3 sequence was {@code trySetEntity → notifyChanged →
 * botRegistry.register → liveEntityRegistry.register} with no lock spanning
 * the gap. A tick read at {@code @Order(10)} fired between
 * {@code worldGrid.trySetEntity} and {@code liveEntityRegistry.register}
 * would see the entity on the grid but absent from the registry —
 * entity-list iteration skipped it for one tick. Benign in the Golden gate
 * (batch-register before tick 1) but real on production multi-bot ramps.
 *
 * <p><b>Fix</b>: register in {@link LiveEntityRegistry} BEFORE
 * {@code worldGrid.trySetEntity}; on {@code trySetEntity} failure, immediately
 * unregister to roll back. The opposite transient ("registry has it, grid
 * doesn't") is benign: tick-handler consumers re-derive the entity from
 * {@code worldGrid.getCell(entry.position()).occupant()}, treating
 * non-Particle/BondedPair/CompositeMember occupants (including null) as a
 * benign skip without throwing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=8",
        "paralife.world.height=8",
        "paralife.simulation.energy-decay-per-tick=0",
        "paralife.simulation.types.catalyst.decay-per-tick=0",
        "paralife.simulation.types.membrane.decay-per-tick=0",
        "paralife.simulation.types.spore.decay-per-tick=0",
        "paralife.simulation.nutrient-spawn-probability=0.0",
        "paralife.simulation.overcrowding-threshold=8",
        "paralife.simulation.events.enabled=false",
})
class RegisterAtomicityTest {

    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired ApplicationEventPublisher publisher;

    @BeforeEach
    void resetAll() {
        worldGrid.clear();
        liveEntityRegistry.clearForTest();
        botRegistry.clear();
    }

    /**
     * Simulate the post-H3 transient where {@link LiveEntityRegistry} has been
     * registered but {@code WorldGrid.trySetEntity} has not yet been called.
     * Insert an orphan registry entry at an empty cell, then fire a tick.
     *
     * <p>Tick handlers (SimulationEngine, EnvironmentEngine) iterate
     * {@code liveEntityRegistry.snapshot()} and re-derive the live entity from
     * {@code worldGrid.getCell(entry.position()).occupant()}. The empty cell
     * must produce a benign skip — not an NPE, not a ClassCastException,
     * nothing observable beyond "this entry was skipped this tick."
     */
    @Test
    void tickToleratesRegistryEntryWithEmptyGridCell() {
        // Arrange: insert an orphan registry entry at (4,4) with the cell empty.
        Position orphanPos = new Position(4, 4);
        liveEntityRegistry.register("orphan-entity", orphanPos, Optional.empty());
        assertThat(worldGrid.getCell(orphanPos.x(), orphanPos.y()).hasOccupant())
                .as("orphan registry entry must point at an empty cell to reproduce the transient")
                .isFalse();
        assertThat(liveEntityRegistry.size()).isEqualTo(1);

        // Act + Assert: tick must not throw — consumers handle the transient gracefully.
        assertThatCode(() -> publisher.publishEvent(new TickEvent(1L)))
                .as("Tick handlers must skip orphan registry entries without crashing")
                .doesNotThrowAnyException();

        // Registry retains the orphan (no auto-cleanup on transient skip — caller's job).
        assertThat(liveEntityRegistry.size())
                .as("Tick handlers must not unregister the orphan entry as a side-effect")
                .isEqualTo(1);
    }

    /**
     * Validate the H3 rollback semantics: after register-first followed by an
     * intentional rollback (mimicking trySetEntity failure), the registry is
     * clean and a fresh registration succeeds.
     */
    @Test
    void registerThenRollbackRestoresCleanState() {
        Position pos = new Position(2, 2);
        liveEntityRegistry.register("retry-entity", pos, Optional.empty());
        assertThat(liveEntityRegistry.size()).isEqualTo(1);

        // Mimic the H3 rollback path on trySetEntity failure.
        liveEntityRegistry.unregister("retry-entity");
        assertThat(liveEntityRegistry.size()).isZero();

        // Re-register at a different position must not throw on conflicting prior id.
        liveEntityRegistry.register("retry-entity", new Position(3, 3), Optional.empty());
        assertThat(liveEntityRegistry.size()).isEqualTo(1);
        assertThat(liveEntityRegistry.snapshot().get(0).position()).isEqualTo(new Position(3, 3));
    }
}
