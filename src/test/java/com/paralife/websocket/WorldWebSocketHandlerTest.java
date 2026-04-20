package com.paralife.websocket;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
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

    private WebSocketSession session;
    private Map<String, Object> attrs;

    @BeforeEach
    void setUp() {
        session = mock(WebSocketSession.class);
        attrs = new HashMap<>();
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
    }

    @Test
    void malformedFrameProducesError400() throws Exception {
        // "GARBAGE" starts with 'G' which the codec's frame-type switch rejects
        // as "Unknown frame type". Handler must wrap this in E|400.
        handler.handleMessage(session, new TextMessage("GARBAGE"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
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

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        boolean sawCap = captor.getAllValues().stream()
                .anyMatch(m -> m.getPayload().startsWith("E|429"));
        assertTrue(sawCap, "Expected E|429 after respawn cap exceeded");
    }
}
