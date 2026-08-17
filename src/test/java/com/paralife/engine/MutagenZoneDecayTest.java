package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * E-2c / EARS-5 — zone decay must run on the same schedule while an outbreak
 * is active, not only once the outbreak has gone idle.
 *
 * <p>gossip-probability is pinned to 0.0 (class-level override of
 * {@link MutagenTest}'s 1.0): with gossip disabled, the age-out sweep is the
 * only mechanism that can clear a cell, which is exactly the gate under test.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.tick.auto-start=false",
        "paralife.simulation.enabled=true",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.events.mutagen.damage-per-tick=2",
        "paralife.simulation.events.mutagen.infection-duration-min=10",
        "paralife.simulation.events.mutagen.infection-duration-max=10",
        "paralife.simulation.events.mutagen.buff-duration-multiplier=5",
        "paralife.simulation.events.mutagen.cure-ticks=3",
        "paralife.simulation.events.mutagen.attack-cure-reduction=3",
        "paralife.simulation.events.mutagen.gossip-probability=0.0",
        "paralife.simulation.events.mutagen.strain-mutation-chance=0.0",
        "paralife.simulation.events.mutagen.zone-decay-ticks=5",
        "paralife.simulation.events.mutagen.outbreak-lifetime-ticks=20",
        "paralife.bonding.bonding-probability=0.0",
        "paralife.composite.dissolution-chance=0.0"
})
class MutagenZoneDecayTest {

    @Autowired WorldGrid worldGrid;
    @Autowired EnvironmentEngine env;

    @BeforeEach
    void setUp() {
        worldGrid.clear();
        env.resetMutagenStateForTest();
    }

    // EARS-5 — decay runs during an active outbreak.
    // Fixture: gossip-probability = 0.0 (class-level), zone-decay-ticks = 5.
    // forceSpawnMutagenForTest(0L, origin, strain, 300) — lifetime is a PARAMETER, so
    //   it is test-owned; it must far exceed zoneDecayTicks so the whole window under
    //   test sits strictly INSIDE the active period.
    // stampMutagenForTest(target, strain) THEN setMutagenLastReinforcedTickForTest(target, 0L)
    //   — that order matters: the stamp resets the reinforcement tick to 0L
    //   (EnvironmentEngine.java:1651), so setting it first would be silently undone.
    // Advance past zoneDecayTicks via advanceMutagenForTest.
    //
    // Assert: target is strain 0, AND activeMutagenEvent() != null at the moment of
    //   assertion — without that second half the test passes for the wrong reason if
    //   the outbreak quietly expired and the OLD idle-only path did the clearing.
    // Positive control: a second cell whose timestamp is within the last zoneDecayTicks
    //   is still set, proving the sweep discriminates by age rather than clearing all.
    @Test
    void decaySweepClearsAgedCellWhileOutbreakActive() {
        Position origin = new Position(1, 1);
        Position aged = new Position(5, 5);
        Position fresh = new Position(6, 6);

        env.forceSpawnMutagenForTest(0L, origin, 3, 300);

        env.stampMutagenForTest(aged, 3);
        env.setMutagenLastReinforcedTickForTest(aged, 0L);

        env.stampMutagenForTest(fresh, 3);
        env.setMutagenLastReinforcedTickForTest(fresh, 4L);

        env.advanceMutagenForTest(5L);

        assertThat(env.activeMutagenEvent()).isNotNull();
        assertThat(env.mutagenStrainAtForTest(aged)).isEqualTo(0);
        assertThat(env.mutagenStrainAtForTest(fresh)).isEqualTo(3);
    }

    // Dead-window fix — the outbreak must END when its bloom has fully decayed and
    // it has stopped growing, not linger 'active' over an empty grid until
    // outbreakLifetimeTicks (which the bloom outlives by ~2/3 of its span). While
    // activeMutagenEvent() stays non-null, spawnMutagen refuses a fresh outbreak
    // (EnvironmentEngine.java:524), so the coupling silently suppressed all mutagen
    // for the dead remainder of every lifetime.
    // Fixture: gossip-probability = 0.0 (class-level), zone-decay-ticks = 5.
    // Only the origin cell exists; it decays 5 ticks after spawn, ~295 ticks before
    // the nominal 300-tick lifetime passed here.
    @Test
    void outbreakEndsWhenFieldFullyDecaysNotAtLifetime() {
        Position origin = new Position(1, 1);
        // growTicks=2 → frozen from tick 2; lifetime=300 → isExpired only at tick 300.
        env.forceSpawnMutagenForTest(0L, origin, 3, 300, 2);

        // Positive control: while the origin cell survives, the outbreak is active.
        env.advanceMutagenForTest(3L);
        assertThat(env.mutagenStrainAtForTest(origin))
                .as("origin still colonized before it ages out").isEqualTo(3);
        assertThat(env.activeMutagenEvent())
                .as("outbreak stays active while its field persists").isNotNull();

        // Age the origin past zoneDecayTicks (6 - 0 >= 5) → field empties.
        env.advanceMutagenForTest(6L);
        assertThat(env.mutagenStrainAtForTest(origin))
                .as("origin has aged out — field is now empty").isEqualTo(0);
        assertThat(env.activeMutagenEvent())
                .as("outbreak ends with its field, not at the 300-tick lifetime")
                .isNull();
    }
}
