package com.paralife.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.paralife.codec.Frame;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * HeuristicBrain determinism + BotState-gated branches.
 *
 * <p>Under the Phase 15 refactor the brain is a pure function of
 * {@code (Frame.TickFrame, BotState, Random)}. Given the same inputs and a
 * seeded {@link Random}, two invocations must return an {@link
 * Frame.ActionFrame} that is {@code equals}-equal.
 *
 * <p>Also pins the authority-tier contract from SCHEMA §7:
 * <ul>
 *   <li>BONDED_SECONDARY — null decision (primary decides).</li>
 *   <li>COMPOSITE_MEMBER with role 1-5 — null decision (authority-lite
 *       client-side brain DEFERRED post-MVP; server auto-fallback covers).</li>
 *   <li>COMPOSITE_MEMBER with role 0 (LOCOMOTOR) — {@code a|V|<3 numpad chars>}
 *       per SCHEMA §8.6.</li>
 *   <li>SOLO / BONDED_PRIMARY — full action tier (M/E/A/R).</li>
 * </ul>
 */
class HeuristicBrainDeterminismTest {

    @Test
    void entityStatusBitConstantsMatchSchema() {
        // SCHEMA.md §8.1.2 entityState contract — the bot's decoder must agree with the
        // server's encoder (EnvironmentEngine) and the schema. Pinned to literals so a future
        // drift on EITHER side is caught (the server side is pinned in TickBroadcasterProjectionTest).
        assertEquals(0x01, HeuristicBrain.ENTITY_STATUS_STARVING, "STARVING = bit 0");
        assertEquals(0x02, HeuristicBrain.ENTITY_STATUS_MUTATING, "MUTATING = bit 1");
        assertEquals(0x04, HeuristicBrain.ENTITY_STATUS_BUFFED, "BUFFED = bit 2");
    }

    @Test
    void sameSeedProducesIdenticalDecisionForSolo() {
        HeuristicBrain brain = new HeuristicBrain(70);
        Frame.TickFrame frame = quietSoloFrame();
        BotState state = BotState.initial('C');
        Frame.ActionFrame a = brain.decide(frame, state, new Random(42L));
        Frame.ActionFrame b = brain.decide(frame, state, new Random(42L));
        assertNotNull(a, "SOLO bot must produce an action frame");
        assertEquals(a, b, "Same (frame, state, seed) must yield equal ActionFrame");
    }

    @Test
    void bondedSecondaryReturnsNull() {
        HeuristicBrain brain = new HeuristicBrain(70);
        // 'D' = bonded secondary for CAT primary per SCHEMA §8.2.
        BotState state = BotState.initial('C').withChangeCode('D');
        Frame.ActionFrame decision = brain.decide(quietSoloFrame(), state, new Random(1L));
        assertNull(decision, "BONDED_SECONDARY must not submit actions");
    }

    @Test
    void compositeNonLocomotorReturnsNull() {
        HeuristicBrain brain = new HeuristicBrain(70);
        // '1' = FEEDER — authority-lite, deferred post-MVP (null expected).
        BotState feeder = BotState.initial('C').withChangeCode('1');
        Frame.ActionFrame d1 = brain.decide(quietSoloFrame(), feeder, new Random(1L));
        assertNull(d1, "Authority-lite FEEDER must submit no action (deferred post-MVP)");

        // '5' = SENSOR — passive, never submits.
        BotState sensor = BotState.initial('C').withChangeCode('5');
        Frame.ActionFrame d5 = brain.decide(quietSoloFrame(), sensor, new Random(1L));
        assertNull(d5, "Passive SENSOR must submit no action");

        // '3' = DEFENDER — passive.
        BotState defender = BotState.initial('C').withChangeCode('3');
        Frame.ActionFrame d3 = brain.decide(quietSoloFrame(), defender, new Random(1L));
        assertNull(d3, "Passive DEFENDER must submit no action");
    }

    @Test
    void locomotorEmitsVoteActionFrame() {
        HeuristicBrain brain = new HeuristicBrain(70);
        // '0' = LOCOMOTOR per SCHEMA §7.
        BotState state = BotState.initial('C').withChangeCode('0');
        Frame.ActionFrame decision = brain.decide(quietSoloFrame(), state, new Random(1L));
        assertNotNull(decision, "LOCOMOTOR must submit a vote");
        assertEquals('V', decision.verb(),
                "LOCOMOTOR must emit V (vote) per SCHEMA §8.6");
        assertEquals(3, decision.arg().orElseThrow().length(),
                "V arg is 3 numpad chars (IRV ranking)");
        // Each rank char must be a numpad digit '1'..'9' per SCHEMA §8.6.
        for (int i = 0; i < 3; i++) {
            char c = decision.arg().get().charAt(i);
            org.junit.jupiter.api.Assertions.assertTrue(c >= '1' && c <= '9',
                    "V rank char must be '1'..'9', got '" + c + "' at index " + i);
        }
    }

    @Test
    void bondedPrimaryAlsoHasFullAuthority() {
        HeuristicBrain brain = new HeuristicBrain(70);
        BotState state = BotState.initial('S').withChangeCode('C'); // bonded primary
        Frame.ActionFrame decision = brain.decide(quietSoloFrame(), state, new Random(7L));
        assertNotNull(decision, "BONDED_PRIMARY must produce an action");
    }

    /**
     * Quiet "no neighbours, no events, no effects" frame — exercises the
     * random-walk fallback branch while avoiding any codec edge-case coupling.
     */
    private static Frame.TickFrame quietSoloFrame() {
        return new Frame.TickFrame(
                1L, 10, 10, 50, 100, 2,
                List.of(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), List.of());
    }
}
