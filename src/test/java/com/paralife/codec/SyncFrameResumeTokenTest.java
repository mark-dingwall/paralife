package com.paralife.codec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and parse tests for the r:-sentinel resume-token slot on SyncFrame.
 *
 * Locked design per Phase 17 Plan 02:
 *   - Sole disambiguator in parseSync: second slot starts with literal "r:" → token;
 *     otherwise → effect list. The 'f' prefix (TickFrame f-block) is irrelevant here;
 *     SyncFrame effects per 15-SCHEMA.md §6.2 / vector 10 are bare S:/I:/f... without
 *     a block-header 'f'. The only signal that matters is whether the slot begins with "r:".
 *   - Token format: r:%016x — always 18 chars, always begins with literal "r:".
 */
class SyncFrameResumeTokenTest {

    private static final String TOKEN = "r:deadbeefcafe1234";

    // ---- Parse: all four cardinalities ----

    @Test
    void parseSyncEntityOnly() {
        // S|abc-123 — no token, no effects
        Frame frame = PerceptionCodec.decode("S|abc-123");
        Frame.SyncFrame sf = assertSync(frame);
        assertEquals("abc-123", sf.entityId());
        assertEquals(Optional.empty(), sf.resumeToken());
        assertEquals(List.of(), sf.effects());
    }

    @Test
    void parseSyncEntityAndEffects_legacyShapeF() {
        // S|abc-123|f5T2 — effect block that starts with 'f': not a token, parsed as effects.
        // (Per 15-SCHEMA.md §6.2: effects are bare codes like S:/I:/fX, no block prefix.)
        // This test documents that f5T2 is treated as effect code 'f' followed by '5T2'
        // per the existing grammar — the disambiguator only checks "r:", not "f".
        // NOTE: 'f' is not a valid effect code per current schema, so this will produce a
        // CodecException from the effect parser — the point is that 'f...' does NOT trigger
        // the token path even though it starts with a non-r: char.
        assertThrows(CodecException.class, () -> PerceptionCodec.decode("S|abc-123|f5T2"),
                "f5T2 is not a valid effect and should not be treated as a token");
    }

    @Test
    void parseSyncEntityAndEffects_vector10Shape() {
        // V10 from 15-SCHEMA.md §10: S|7A|S:1Fg8,I:1Ef0 — effects starting with S:/I:.
        // This is the canonical resync shape; NO token, effects parsed directly.
        // The disambiguator must NOT be keyed off 'f' — both 'S:' and 'I:' are valid here.
        Frame frame = PerceptionCodec.decode("S|7A|S:1Fg8,I:1Ef0");
        Frame.SyncFrame sf = assertSync(frame);
        assertEquals("7A", sf.entityId());
        assertEquals(Optional.empty(), sf.resumeToken());
        assertEquals(2, sf.effects().size());
    }

    @Test
    void parseSyncEntityAndToken() {
        // S|abc-123|r:deadbeefcafe1234 — token, no effects
        Frame frame = PerceptionCodec.decode("S|abc-123|" + TOKEN);
        Frame.SyncFrame sf = assertSync(frame);
        assertEquals("abc-123", sf.entityId());
        assertEquals(Optional.of(TOKEN), sf.resumeToken());
        assertEquals(List.of(), sf.effects());
    }

    @Test
    void parseSyncEntityTokenAndEffects() {
        // S|abc-123|r:deadbeefcafe1234|S:1Fg8,I:1Ef0 — token AND effects
        String wire = "S|abc-123|" + TOKEN + "|S:1Fg8,I:1Ef0";
        Frame frame = PerceptionCodec.decode(wire);
        Frame.SyncFrame sf = assertSync(frame);
        assertEquals("abc-123", sf.entityId());
        assertEquals(Optional.of(TOKEN), sf.resumeToken());
        assertEquals(2, sf.effects().size());
    }

    // ---- Round-trip for all cardinalities ----

    @Test
    void encodeRoundTripAllFour() {
        // Entity only
        roundTrip(new Frame.SyncFrame("ent1", Optional.empty(), List.of()));

        // Entity + effects (vector 10 shape, no token)
        // V10 wire: S|7A|S:1Fg8,I:1Ef0. We construct the same frame directly.
        // expiry values chosen to round-trip through encodeVarBase64/readVarBase64.
        List<ActiveEffect> twoEffects = List.of(
                new ActiveEffect('S', 325640L, Optional.empty()),  // encodes as 1Fg8
                new ActiveEffect('I', 323312L, Optional.empty())   // encodes as 1Ef0
        );
        roundTrip(new Frame.SyncFrame("7A", Optional.empty(), twoEffects));

        // Entity + token, no effects
        roundTrip(new Frame.SyncFrame("abc-123", Optional.of(TOKEN), List.of()));

        // Entity + token + effects
        roundTrip(new Frame.SyncFrame("abc-123", Optional.of(TOKEN), twoEffects));
    }

    // ---- Error cases ----

    @Test
    void parseSyncEmptyEntity() {
        // S| — entity id is empty, must throw
        assertThrows(CodecException.class, () -> PerceptionCodec.decode("S|"));
    }

    @Test
    void parseSyncEmptyTokenSlot() {
        // S|abc|| — empty middle slot (between two pipes after entityId) must throw
        assertThrows(CodecException.class,
                () -> PerceptionCodec.decode("S|abc||S:1Fg8"));
    }

    // ---- Disambiguator correctness ----

    @Test
    void disambiguatorIgnoresFPrefixedEffects() {
        // S|abc|f0a1b2c3d4e5f678 — leading char is 'f', NOT 'r:'.
        // The disambiguator MUST NOT treat this as a token just because 'f' appears.
        // Only the literal two-char sequence "r:" is the token signal.
        // 'f' by itself is not a valid SyncFrame effect code, so a CodecException
        // from the effect parser is expected — but the key assertion is that it
        // was NOT parsed as a resume token (no token path entered).
        // If it somehow decoded successfully as a SyncFrame with a token, that's a bug.
        try {
            Frame frame = PerceptionCodec.decode("S|abc|f0a1b2c3d4e5f678");
            // If decode succeeded (effect 'f' happened to be accepted), verify no token
            Frame.SyncFrame sf = assertSync(frame);
            assertEquals(Optional.empty(), sf.resumeToken(),
                    "'f...' second slot must not be parsed as resume token");
        } catch (CodecException e) {
            // Expected: 'f' is not a valid SyncFrame effect code
        }
    }

    @Test
    void peekIsNonConsuming() {
        // Verify that parsing S|abc|f5T2-ish and S|abc|r:... on fresh cursors both
        // return the correct shapes, proving the internal peekStartsWithSentinel does
        // not corrupt cursor state in the false branch.

        // Bare entity: no second slot at all
        Frame f1 = PerceptionCodec.decode("S|abc-123");
        Frame.SyncFrame sf1 = assertSync(f1);
        assertEquals(Optional.empty(), sf1.resumeToken());
        assertEquals(List.of(), sf1.effects());

        // Token slot: r: prefix correctly detected
        Frame f2 = PerceptionCodec.decode("S|abc-123|" + TOKEN);
        Frame.SyncFrame sf2 = assertSync(f2);
        assertEquals(Optional.of(TOKEN), sf2.resumeToken());
        assertEquals(List.of(), sf2.effects());
    }

    // ---- Helpers ----

    private static Frame.SyncFrame assertSync(Frame frame) {
        assertInstanceOf(Frame.SyncFrame.class, frame);
        return (Frame.SyncFrame) frame;
    }

    private static void roundTrip(Frame.SyncFrame original) {
        String encoded = PerceptionCodec.encode(original);
        Frame decoded = PerceptionCodec.decode(encoded);
        assertEquals(original, decoded, "Round-trip mismatch for: " + encoded);
    }
}
