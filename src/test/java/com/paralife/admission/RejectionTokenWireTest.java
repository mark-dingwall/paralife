package com.paralife.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A28 — pins each {@code ADMISSION.md} §1 rejection token to its exact on-wire literal
 * {@code E|<code>|<token>}, closing the "constant-referential, not literal-pinned" gap (§0).
 *
 * <p>Every other default-suite token assertion compares {@code token()} to the
 * {@link RejectionToken} <em>constant</em> — so renaming a constant's string value (e.g.
 * {@code WORLD_FULL = "world-full"} → {@code "full"}) shifts both sides together and stays green.
 * Here the production constant is encoded through the real wire path and compared to an
 * <strong>independent hand-written literal</strong>; a rename turns exactly that row red.
 *
 * <p>The {@code <code>} in each expected literal is test-owned scaffolding transcribing the §1
 * taxonomy for traceability — it is echoed verbatim by the codec, so it can never independently
 * drive red. The only production-owned red/green driver is the token constant's string value.
 * This pins the wire <em>encoding</em> boundary, not gate condition→token routing (§0 Non-Goals;
 * 4 of the 9 tokens are emitted outside {@code AdmissionGate}).
 */
class RejectionTokenWireTest {

    /** One row per §1 taxonomy entry: (production constant, HTTP-style code, independent wire literal). */
    static Stream<Arguments> tokenWireContract() {
        return Stream.of(
                Arguments.of(RejectionToken.MALFORMED, 400, "E|400|malformed"),
                Arguments.of(RejectionToken.NO_ACTIVE_ENTITY, 404, "E|404|no-active-entity"),
                Arguments.of(RejectionToken.RECONNECT_REQUIRED, 408, "E|408|reconnect-required"),
                Arguments.of(RejectionToken.ALREADY_REGISTERED, 409, "E|409|already-registered"),
                Arguments.of(RejectionToken.WORLD_FULL, 429, "E|429|world-full"),
                Arguments.of(RejectionToken.RESPAWN_CAP, 429, "E|429|respawn-cap"),
                Arguments.of(RejectionToken.TICK_OVERLOAD, 429, "E|429|tick-overload"),
                Arguments.of(RejectionToken.MAINTENANCE, 429, "E|429|maintenance"),
                Arguments.of(RejectionToken.GRID_FULL, 503, "E|503|grid-full"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("tokenWireContract")
    @DisplayName("A28 — each §1 rejection token encodes to its exact wire literal")
    void tokenEncodesToExactWireLiteral(String token, int code, String expectedWire) {
        String wire = PerceptionCodec.encode(new Frame.ErrorFrame(code, Optional.of(token)));
        assertThat(wire).isEqualTo(expectedWire);
    }
}
