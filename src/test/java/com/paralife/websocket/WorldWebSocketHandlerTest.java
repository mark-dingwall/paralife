package com.paralife.websocket;

import com.paralife.admission.OutboundSender;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

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
        Frame decoded = PerceptionCodec.decode(out);
        assertTrue(decoded instanceof Frame.ErrorFrame err && err.code() == 400,
                "Expected E|400, got: " + out);
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
