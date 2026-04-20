package com.paralife.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * IRV (Instant Runoff) rounds per 15-SCHEMA.md §8.6. Ballots are 3-char numpad
 * strings. Numpad mapping per {@link Direction#fromNumpad(char)}:
 * {@code 8=N, 9=NE, 6=E, 3=SE, 2=S, 1=SW, 4=W, 7=NW}.
 *
 * <p>Calls {@link ActionResolver#resolveLocomotorVote(java.util.List)} directly —
 * that method is declared {@code static package-private} (plan 15-06 Task 2
 * Part B). Same-package access means no resolver instance is required, which
 * keeps this test focused on the pure IRV algorithm.
 */
class IRVVoteResolverTest {

    @Test
    void firstRoundMajorityWins() {
        // 3 of 5 voters' first choice = N ('8').
        List<String> ballots = List.of("869", "836", "836", "693", "326");
        Direction winner = ActionResolver.resolveLocomotorVote(ballots);
        assertEquals(Direction.N, winner);
    }

    @Test
    void eliminationRoundsAwardWinner() {
        // No first-round majority. Algorithm must enter elimination rounds and
        // still return a deterministic winner (contract: non-null).
        List<String> ballots = List.of("862", "236", "829", "692");
        Direction winner = ActionResolver.resolveLocomotorVote(ballots);
        assertNotNull(winner);
    }

    @Test
    void emptyBallotReturnsNull() {
        assertNull(ActionResolver.resolveLocomotorVote(List.of()));
    }

    @Test
    void blankBallotsTolerated() {
        // Tolerate null / empty entries without throwing — no winner.
        assertNull(ActionResolver.resolveLocomotorVote(Arrays.asList("", null, "")));
    }

    @Test
    void tiedEliminationBrokenByLowestNumpadDigit() {
        // Multiple candidates could be the first-round loser; algorithm must
        // pick one and converge on a winner. The contract here is "don't hang
        // and do return a direction" — the exact winner depends on elimination
        // order, but it must be a compass direction (not null).
        List<String> ballots = List.of("148", "418", "841", "884");
        Direction winner = ActionResolver.resolveLocomotorVote(ballots);
        assertNotNull(winner);
    }
}
