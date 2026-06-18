package com.paralife.bot;

import com.paralife.codec.CellEntry;
import com.paralife.codec.Coord;
import com.paralife.codec.Frame;
import com.paralife.codec.KindData;
import com.paralife.engine.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for HeuristicBrain.decideLocomotor — D-06 steering heuristic.
 *
 * <p>Verifies that the LOCOMOTOR's IRV ballot is derived from frame.cells() via a named
 * rankDirections helper (direction ranking only, deduped directions, no fresh Random), steers
 * toward SENSOR-only-visible food, repels from a predator to its opposite direction, and always
 * emits a codec-valid 3-distinct-char 'V' ballot.
 *
 * <p>Sign convention (round-4 correction): {@code N(0,-1)} / {@code S(0,+1)} — positive dy is
 * SOUTH. So {@code Coord.Relative(0,-2)} is NORTH ('8') and {@code Coord.Relative(0,+2)} is
 * SOUTH ('2').
 */
class HeuristicBrainLocomotorTest {

    private static final HeuristicBrain BRAIN = new HeuristicBrain(70);

    // ================================================================
    // BotState helpers
    // ================================================================

    /** COMPOSITE_MEMBER with role 0 = LOCOMOTOR. */
    private static BotState locomotorState() {
        return BotState.initial('C').withChangeCode('0');
    }

    // ================================================================
    // CellEntry factory helpers
    // ================================================================

    /** A nutrient ('F') cell at the given relative coord. */
    private static CellEntry nutrientAt(int dx, int dy) {
        return new CellEntry(new Coord.Relative(dx, dy), 1,
                Optional.of(new KindData.Simple('F')),
                OptionalInt.empty(), OptionalInt.empty());
    }

    /**
     * A predator cell for a Catalyst bot (Catalyst's predator is Membrane = 'M').
     * Uses presence=1 (entity only).
     */
    private static CellEntry predatorAt(int dx, int dy) {
        // CATALYST is eaten by MEMBRANE; a Catalyst bot sees Membrane as predator.
        return new CellEntry(new Coord.Relative(dx, dy), 1,
                Optional.of(new KindData.Simple('M')),
                OptionalInt.empty(), OptionalInt.empty());
    }

    /** Build a LOCOMOTOR TickFrame (sensorRadius=2 = 5x5 default). */
    private static Frame.TickFrame locomotorFrame(List<CellEntry> cells) {
        return new Frame.TickFrame(
                1L, 512, 512, 50, 100, 2,
                cells, Optional.empty(), List.of(), List.of(),
                Optional.empty(), List.of());
    }

    /** Assert the ballot is codec-valid: verb 'V', exactly 3 chars, each in '1'..'9'. */
    private static void assertCodecValid(Frame.ActionFrame action) {
        assertThat(action).isNotNull();
        assertThat(action.verb()).isEqualTo('V');
        String ballot = action.arg().orElseThrow();
        assertThat(ballot).hasSize(3);
        assertThat(ballot.chars().allMatch(c -> c >= '1' && c <= '9'))
                .as("all ballot chars must be '1'..'9', got: " + ballot)
                .isTrue();
    }

    // ================================================================
    // (0) SIGN-CONVENTION PIN — pins the dy sign convention at the test boundary.
    //     Coord.Relative(0,-2) → fromDxDy(0, signum(-2)) = fromDxDy(0,-1) → Direction.N → '8'.
    //     Independent of the brain.
    // ================================================================

    @Test
    void signConventionPin_negDyIsNorth() {
        // PIN: Coord.Relative(0,-2) maps via fromDxDy(signum(0), signum(-2)) = fromDxDy(0,-1) to Direction.N.
        // Therefore NORTH is numpad '8', and (0,+2) is SOUTH '2'.
        assertThat(HeuristicBrain.fromDxDy(0, Integer.signum(-2)))
                .as("fromDxDy(0,-1) must be Direction.N: negative dy = north in N(0,-1) convention")
                .isEqualTo(Direction.N);
        assertThat(Direction.numpadOf(Direction.N))
                .as("Direction.N must be numpad '8'")
                .isEqualTo('8');
    }

    // ================================================================
    // (1) SENSOR-FOOD-STEER — LOCOMOTOR top rank points toward SENSOR-only-visible nutrient.
    //     Coord.Relative(0,-2) is NORTH of LOCOMOTOR — outside radius-1 adjacency (dist=2),
    //     inside a SENSOR 5x5 window. Expected top rank: '8' (north).
    // ================================================================

    @Test
    void sensorFoodSteer_topRankTowardNorthNutrient() {
        // SENSOR-visible nutrient at (0,-2) — north, dist=2 (outside own 8-cell adjacency).
        Frame.TickFrame frame = locomotorFrame(List.of(nutrientAt(0, -2)));
        Frame.ActionFrame action = BRAIN.decide(frame, locomotorState(), new Random(42));

        assertCodecValid(action);
        String ballot = action.arg().orElseThrow();
        // Top rank must be '8' (north), the direction toward (0,-2).
        assertThat(ballot.charAt(0))
                .as("top rank must point north ('8') toward SENSOR-visible nutrient at (0,-2)")
                .isEqualTo('8');
    }

    // ================================================================
    // (2) THREAT-AVOID — PREDATOR-ONLY frame, top rank == OPPOSITE direction.
    //     C5 (round-3): predator-only frame so no food/prey confounds the cascade.
    //     Predator at (0,-1) = ADJACENT north (dist=1) — flee gate fires at maxDist=1.
    //     Expected top rank: '2' (south, the antipode of north).
    //     NOTE: dist-2 predator (SENSOR-distance threat) is ACCEPTED TECH DEBT —
    //           the flee gate is `adjacent(predators, 1)` so dist-2 predators are filtered out.
    //           This test uses dist-1 (the in-scope NEAR case).
    // ================================================================

    @Test
    void threatAvoid_predatorOnlyFrame_topRankIsOppositeDirection() { // THREAT-AVOID
        // PREDATOR-ONLY frame: Membrane cell at (0,-1) = adjacent north.
        // No food, no prey — predator at dist=1 → flee gate fires → top rank = south antipode.
        Frame.TickFrame frame = locomotorFrame(List.of(predatorAt(0, -1)));
        Frame.ActionFrame action = BRAIN.decide(frame, locomotorState(), new Random(42));

        assertCodecValid(action);
        String ballot = action.arg().orElseThrow();

        // Predator is at (0,-1) = NORTH. The OPPOSITE/repulsion direction is SOUTH = '2'.
        // Assert top rank EQUALS the antipode, not merely "not-toward-predator" (C5 strengthening).
        assertThat(ballot.charAt(0))
                .as("top rank must be south '2' (opposite of north predator at (0,-1))")
                .isEqualTo('2');
    }

    // ================================================================
    // (3) DIRECTION-RANKING-NOT-ACTION — verb is always 'V', never A/E/M.
    //     Verified on food, threat, and empty scenarios above/below.
    //     An explicit multi-scenario check.
    // ================================================================

    @Test
    void verbIsAlwaysV_notActionVerb() {
        BotState state = locomotorState();

        // Food scenario
        Frame.ActionFrame foodAction = BRAIN.decide(
                locomotorFrame(List.of(nutrientAt(1, 0))), state, new Random(42));
        assertThat(foodAction.verb()).isEqualTo('V');

        // Threat scenario
        Frame.ActionFrame threatAction = BRAIN.decide(
                locomotorFrame(List.of(predatorAt(0, -1))), state, new Random(42));
        assertThat(threatAction.verb()).isEqualTo('V');

        // Empty scenario
        Frame.ActionFrame emptyAction = BRAIN.decide(
                locomotorFrame(List.of()), state, new Random(42));
        assertThat(emptyAction.verb()).isEqualTo('V');
    }

    // ================================================================
    // (4) EMPTY-FRAME FALLBACK — 3 DISTINCT valid numpad chars from topThreeDirections.
    // ================================================================

    @Test
    void emptyFrameFallback_threeDistinctValidChars() {
        Frame.TickFrame frame = locomotorFrame(List.of());
        Frame.ActionFrame action = BRAIN.decide(frame, locomotorState(), new Random(42));

        assertCodecValid(action);
        String ballot = action.arg().orElseThrow();
        Set<Character> distinct = Set.of(ballot.charAt(0), ballot.charAt(1), ballot.charAt(2));
        assertThat(distinct)
                .as("empty-frame fallback ballot must have 3 DISTINCT chars, got: " + ballot)
                .hasSize(3);
    }

    // ================================================================
    // (5) MULTI-SAME-DIRECTION DEDUP — C4 (round-3).
    //     Multiple nutrients all north → single '8' rank, filled with 2 distinct fallback dirs.
    //     Proves rankDirections deduped: '888' must NOT be emitted.
    // ================================================================

    @Test
    void multiSameDirectionDedup_northOnlyAttractors() { // MULTI-SAME-DIRECTION DEDUP
        // Three nutrients all north of the LOCOMOTOR — all map to direction '8'.
        Frame.TickFrame frame = locomotorFrame(List.of(
                nutrientAt(0, -2),
                nutrientAt(0, -3),
                nutrientAt(0, -4)
        ));
        Frame.ActionFrame action = BRAIN.decide(frame, locomotorState(), new Random(42));

        assertCodecValid(action);
        String ballot = action.arg().orElseThrow();

        // Top rank must be '8' (north, the attraction direction).
        assertThat(ballot.charAt(0))
                .as("top rank must be '8' (north) when all attractors are north")
                .isEqualTo('8');

        // All three ranks must be DISTINCT — proving '888' was NOT emitted.
        Set<Character> distinct = Set.of(ballot.charAt(0), ballot.charAt(1), ballot.charAt(2));
        assertThat(distinct)
                .as("all three ranks must be DISTINCT (dedup prevents '888'), got: " + ballot)
                .hasSize(3);
    }

    // ================================================================
    // (6) MULTI-TARGET NO-DUP — multiple attractors/repulsors in DIFFERENT directions
    //     → ballot has no duplicate rank (Set size 3).
    // ================================================================

    @Test
    void multiTarget_differentDirections_noDuplicateRank() {
        // Nutrients in different directions + a predator.
        Frame.TickFrame frame = locomotorFrame(List.of(
                nutrientAt(0, -2),  // north
                nutrientAt(2, 0),   // east
                predatorAt(-1, 0)   // west predator (repulsor)
        ));
        Frame.ActionFrame action = BRAIN.decide(frame, locomotorState(), new Random(42));

        assertCodecValid(action);
        String ballot = action.arg().orElseThrow();
        Set<Character> distinct = Set.of(ballot.charAt(0), ballot.charAt(1), ballot.charAt(2));
        assertThat(distinct)
                .as("ballot ranks must all be distinct for multi-target frame, got: " + ballot)
                .hasSize(3);
    }

    // ================================================================
    // (7) DETERMINISM — same frame + same seed → identical ballot across two calls.
    //     No fresh Random inside rankDirections (uses only the passed-in rng).
    // ================================================================

    @Test
    void determinism_sameSeedProducesIdenticalBallot() {
        Frame.TickFrame frame = locomotorFrame(List.of(
                nutrientAt(0, -2),
                nutrientAt(1, 1)
        ));
        BotState state = locomotorState();

        Frame.ActionFrame first = BRAIN.decide(frame, state, new Random(42));
        Frame.ActionFrame second = BRAIN.decide(frame, state, new Random(42));

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.arg().orElseThrow())
                .as("same frame + same seed must yield identical ballot")
                .isEqualTo(second.arg().orElseThrow());
    }
}
