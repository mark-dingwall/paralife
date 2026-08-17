package com.paralife.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.codec.CellEntry;
import com.paralife.codec.Coord;
import com.paralife.codec.Frame;
import com.paralife.codec.KindData;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * EARS-6 — the full-authority chase branch (SOLO / BONDED_PRIMARY) must never emit the
 * solo attack verb {@code 'A'}: {@code ActionResolver}'s {@code case 'A'} only increments
 * restCount, so a chase that ends in {@code 'A'} silently discards the tick.
 */
class HeuristicBrainChaseTest {

    private static final HeuristicBrain BRAIN = new HeuristicBrain(70);

    /** A Catalyst bot's prey is Spore ('S'). */
    private static CellEntry preyAt(int dx, int dy) {
        return new CellEntry(new Coord.Relative(dx, dy), 1,
                Optional.of(new KindData.Simple('S')),
                OptionalInt.empty(), OptionalInt.empty());
    }

    private static Frame.TickFrame chaseFrame(List<CellEntry> cells) {
        return new Frame.TickFrame(
                1L, 512, 512, 50, 100, 2,
                cells, Optional.empty(), List.of(), List.of(),
                Optional.empty(), List.of());
    }

    private static BotState soloState() {
        return BotState.initial('C');
    }

    @Test
    void adjacentPreyNorth_emitsMoveNotAttack() {
        Frame.TickFrame frame = chaseFrame(List.of(preyAt(0, -1)));
        Frame.ActionFrame action = BRAIN.decide(frame, soloState(), new Random(42));

        assertThat(action.verb())
                .as("adjacent prey must draw a move, never the discarded solo attack verb")
                .isEqualTo('M');
        assertThat(action.arg().orElseThrow())
                .as("direction must point north ('8') toward the prey at (0,-1)")
                .isEqualTo("8");
    }

    @Test
    void adjacentPreySoutheast_emitsMoveNotAttack() {
        // Second direction so the assertion can't be satisfied by luck from the
        // fallback random walk (which also returns 'M' but not toward the target).
        Frame.TickFrame frame = chaseFrame(List.of(preyAt(1, 1)));
        Frame.ActionFrame action = BRAIN.decide(frame, soloState(), new Random(42));

        assertThat(action.verb()).isEqualTo('M');
        assertThat(action.arg().orElseThrow())
                .as("direction must point southeast ('3') toward the prey at (1,1)")
                .isEqualTo("3");
    }

    @Test
    void nonAdjacentPrey_stillChasesWithMove() {
        // Positive control: distance-2 prey already yielded 'M' before this fix — proves
        // the chase branch is reached and only the adjacent (distance-1) verb changed.
        Frame.TickFrame frame = chaseFrame(List.of(preyAt(0, -2)));
        Frame.ActionFrame action = BRAIN.decide(frame, soloState(), new Random(42));

        assertThat(action.verb()).isEqualTo('M');
        assertThat(action.arg().orElseThrow()).isEqualTo("8");
    }
}
