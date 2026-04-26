package com.paralife.codec;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and parse tests for the r:-sentinel resume-token slot on RegisterFrame.
 *
 * Locked design per Phase 17 Plan 02:
 *   - Token format: r:%016x — always 18 chars, always begins with literal "r:".
 *   - Disambiguator: second slot starts with "r:" → token; otherwise → error.
 *   - Backward compat: r|C (no token) → RegisterFrame('C', Optional.empty()).
 */
class RegisterFrameResumeTokenTest {

    private static final String TOKEN = "r:0a1b2c3d4e5f6789";

    @Test
    void parseRegisterWithoutTokenBackwardCompat() {
        Frame frame = PerceptionCodec.decode("r|C");
        assertInstanceOf(Frame.RegisterFrame.class, frame);
        Frame.RegisterFrame rf = (Frame.RegisterFrame) frame;
        assertEquals('C', rf.entityType());
        assertEquals(Optional.empty(), rf.resumeToken());
    }

    @Test
    void parseRegisterWithToken() {
        Frame frame = PerceptionCodec.decode("r|C|" + TOKEN);
        assertInstanceOf(Frame.RegisterFrame.class, frame);
        Frame.RegisterFrame rf = (Frame.RegisterFrame) frame;
        assertEquals('C', rf.entityType());
        assertEquals(Optional.of(TOKEN), rf.resumeToken());
    }

    @Test
    void encodeRegisterWithoutToken() {
        String encoded = PerceptionCodec.encode(new Frame.RegisterFrame('C'));
        assertEquals("r|C", encoded);
    }

    @Test
    void encodeRegisterWithToken() {
        String encoded = PerceptionCodec.encode(new Frame.RegisterFrame('C', Optional.of(TOKEN)));
        assertEquals("r|C|" + TOKEN, encoded);
    }

    @Test
    void roundTripWithToken() {
        for (char type : new char[]{'C', 'M', 'S'}) {
            Frame.RegisterFrame original = new Frame.RegisterFrame(type, Optional.of(TOKEN));
            String encoded = PerceptionCodec.encode(original);
            Frame decoded = PerceptionCodec.decode(encoded);
            assertEquals(original, decoded,
                    "Round-trip failed for type=" + type);
        }
    }

    @Test
    void parseRegisterEmptyTokenAfterPipe() {
        // r|C| — empty token slot after pipe must throw
        assertThrows(CodecException.class, () -> PerceptionCodec.decode("r|C|"));
    }

    @Test
    void parseRegisterTokenMissingSentinel() {
        // Token without "r:" prefix must throw; message must mention "r:"
        CodecException ex = assertThrows(CodecException.class,
                () -> PerceptionCodec.decode("r|C|deadbeefcafe1234"));
        assertTrue(ex.getMessage().contains("r:"),
                "Exception message should mention 'r:' sentinel: " + ex.getMessage());
    }

    @Test
    void parseRegisterUnknownTypeWithToken() {
        // r|X|r:deadbeef00000000 — bad entity type must throw
        assertThrows(CodecException.class,
                () -> PerceptionCodec.decode("r|X|r:deadbeef00000000"));
    }
}
