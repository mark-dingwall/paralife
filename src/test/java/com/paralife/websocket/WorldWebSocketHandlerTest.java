package com.paralife.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.paralife.admission.OutboundSender;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.ActionResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Plan 15-06 Task 3 — WorldWebSocketHandler FSM tests.
 *
 * <p>Exercises the two hinge behaviours of the Plan 15-06 handler rewrite:
 * malformed inbound text produces {@code E|400}, and the per-session respawn
 * cap (T-15-04) emits {@code E|429} when exceeded. Both behaviours are
 * encoded as outbound frames through {@link PerceptionCodec} — so the test
 * round-trips the captured send-message bytes back through the decoder to
 * assert on the structured frame rather than the raw string.
 *
 * <p>Uses {@code @SpringBootTest(webEnvironment=NONE)} to pick up real bean
 * wiring (including the {@link com.paralife.engine.AlarmQueue} bean new in
 * plan 15-06). {@code paralife.tick.auto-start=false} prevents the virtual
 * thread tick loop from starting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WorldWebSocketHandlerTest {

    @Autowired
    WorldWebSocketHandler handler;

    @Autowired
    OutboundSender outboundSender;

    // A29 — spy the real bean so the "SHALL NOT queue" conjunct is isolable via verify(never()).
    @SpyBean
    ActionResolver actionResolver;

    private WebSocketSession session;
    private Map<String, Object> attrs;

    @BeforeEach
    void setUp() {
        session = mock(WebSocketSession.class);
        attrs = new HashMap<>();
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        // Phase 17: outbound now flows through OutboundSender's per-session VT drain loop.
        // Attach the mock so offer() succeeds and the drain loop calls session.sendMessage.
        outboundSender.attachSession(session, 16);
    }

    @AfterEach
    void tearDown() {
        outboundSender.detachSession("s1");
    }

    @Test
    void malformedFrameProducesError400() throws Exception {
        // "GARBAGE" starts with 'G' which the codec's frame-type switch rejects
        // as "Unknown frame type". Handler must wrap this in E|400.
        handler.handleMessage(session, new TextMessage("GARBAGE"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        // timeout() polls — VT drain loop sends asynchronously.
        verify(session, timeout(2000).atLeastOnce()).sendMessage(captor.capture());
        String out = captor.getValue().getPayload();
        // A30 — pin the full condition→token routing on the CodecException path, not just the code.
        assertEquals("E|400|malformed", out, "Expected exact malformed wire literal, got: " + out);
    }

    // ── A29/A30 — condition→token routing anchors (ADMISSION §0) ───────────────

    /** Wire strings for server→client-only frames a client must never send (both encode-derived → decodable). */
    static Stream<Arguments> clientIllegalFrames() {
        String syncWire = PerceptionCodec.encode(new Frame.SyncFrame("e1", Optional.empty(), List.of()));
        String tickWire = PerceptionCodec.encode(new Frame.TickFrame(
                0, 0, 0, 0, 0, 0, List.of(), Optional.empty(), List.of(), List.of(), Optional.empty(), List.of()));
        return Stream.of(
                Arguments.of("SyncFrame", syncWire, Frame.SyncFrame.class),
                Arguments.of("TickFrame", tickWire, Frame.TickFrame.class));
    }

    /**
     * A30 — a well-formed but client-illegal frame direction (Sync/Tick, server→client only) is
     * rejected as {@code E|400|malformed}. The instanceof precondition proves the wire genuinely
     * decodes to the illegal-direction frame (reaching that switch arm) rather than silently
     * falling back to the CodecException arm if a future codec change made S/T throw.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("clientIllegalFrames")
    void clientIllegalFrameDirectionRejectedAsMalformed(String label, String wire, Class<?> expectedType)
            throws Exception {
        assertInstanceOf(expectedType, PerceptionCodec.decode(wire),
                label + " wire must decode to the illegal-direction frame, not throw");

        handler.handleMessage(session, new TextMessage(wire));

        verify(session, timeout(2000).atLeastOnce()).sendMessage(argThat(
                msg -> msg instanceof TextMessage tm && tm.getPayload().equals("E|400|malformed")));
    }

    /**
     * A29 (reject conjunct + not-queued conjunct) — an action frame on a session with no active
     * entity (never registered) is rejected {@code E|404|no-active-entity} AND the action is never
     * queued. {@code @SpyBean ActionResolver} isolates the second SHALL.
     */
    @Test
    void actionOnUnregisteredSessionRejectedNoActiveEntity() throws Exception {
        // Valid move action (verb M requires a numpad-digit arg per §8.6) so it decodes to an
        // ActionFrame and reaches handleAction — an arg-less "a|M" would be rejected as malformed.
        String action = PerceptionCodec.encode(new Frame.ActionFrame('M', Optional.of("1")));

        handler.handleMessage(session, new TextMessage(action));

        verify(session, timeout(2000).atLeastOnce()).sendMessage(argThat(
                msg -> msg instanceof TextMessage tm && tm.getPayload().equals("E|404|no-active-entity")));
        // Second SHALL: the unregistered action must not reach the resolver.
        verify(actionResolver, never()).queueAction(eq("s1"), any());
    }

    /**
     * A29 positive control — the same harness DOES route an action to the resolver once the session
     * is registered, proving both the 404 and the not-queued conjuncts are condition-specific (not
     * "every action is dropped"). Registration first (r|C) places an entity, then a|M|1 queues.
     *
     * <p>Uses a distinct session id ("s2"): this class shares "s1" and {@code markDead} does not
     * unregister from the singleton {@code LiveEntityRegistry} in the shared context, so a second
     * fresh registration of entity-s1 would collide ("Conflicting re-register").
     */
    @Test
    void actionOnRegisteredSessionIsQueued() throws Exception {
        WebSocketSession registered = mock(WebSocketSession.class);
        when(registered.getAttributes()).thenReturn(new HashMap<>());
        when(registered.getId()).thenReturn("s2");
        when(registered.isOpen()).thenReturn(true);
        outboundSender.attachSession(registered, 16);
        try {
            handler.handleMessage(registered, new TextMessage("r|C"));
            // Assert registration actually placed an entity (S| sync) before relying on it — a
            // pressured admission/placement would otherwise surface as a confusing queueAction
            // timeout rather than a clear "registration failed".
            verify(registered, timeout(2000).atLeastOnce()).sendMessage(argThat(
                    msg -> msg instanceof TextMessage tm && tm.getPayload().startsWith("S|")));

            String action = PerceptionCodec.encode(new Frame.ActionFrame('M', Optional.of("1")));
            handler.handleMessage(registered, new TextMessage(action));

            verify(actionResolver, timeout(2000)).queueAction(eq("s2"), any());
        } finally {
            // Full unregister (LiveEntityRegistry + grid cell + BotRegistry + admission slot) so the
            // entity does not leak into the shared-context singletons — markDead alone would not.
            handler.cleanupBot(registered);
            outboundSender.detachSession("s2");
        }
    }

    @Test
    void respawnCapEnforced() throws Exception {
        // Sequence: 1 registration + 5 respawns (cap = MAX_RESPAWNS_PER_SESSION),
        // then a 6th attempt that must produce E|429.
        handler.handleMessage(session, new TextMessage("r|C"));
        for (int i = 0; i < 5; i++) {
            handler.markDead(session);
            handler.handleMessage(session, new TextMessage("r|C"));
        }
        // At this point respawnCount = 5 (max). Next attempt must be rejected.
        handler.markDead(session);
        handler.handleMessage(session, new TextMessage("r|C"));

        // The handler drives 7 outbound frames (1 registration ack + 5 respawn acks +
        // the final E|429), all sent ASYNCHRONOUSLY through the per-session VT drain loop.
        // A plain timeout(...).atLeastOnce() returns on the FIRST frame to land, so asserting
        // on the captured values right after races the still-queued E|429 (the LAST frame) —
        // intermittently the cap frame hasn't drained yet and the assertion sees only the
        // earlier acks. (Observed ~12% under a 2-core VT-carrier squeeze.) Bumping the timeout
        // would NOT fix it: atLeastOnce still unblocks on the first frame regardless of the
        // value. Wait for the SPECIFIC E|429 send instead — Mockito polls until that exact
        // frame is observed, which both synchronises on and asserts the cap rejection.
        verify(session, timeout(5000).atLeastOnce()).sendMessage(argThat(
                msg -> msg instanceof TextMessage tm && tm.getPayload().startsWith("E|429")));
    }
}
