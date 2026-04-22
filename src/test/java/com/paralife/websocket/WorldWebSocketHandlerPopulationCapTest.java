package com.paralife.websocket;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.BotRegistry;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.websocket.max-active-entities=1"
})
class WorldWebSocketHandlerPopulationCapTest {

    @Autowired
    WorldWebSocketHandler handler;

    @Autowired
    WorldGrid worldGrid;

    @Autowired
    BotRegistry botRegistry;

    @BeforeEach
    void resetWorld() {
        worldGrid.clear();
        botRegistry.clear();
    }

    @Test
    void populationCapEnforcedWithError429() throws Exception {
        WebSocketSession first = newSession("s1");
        WebSocketSession second = newSession("s2");

        handler.handleMessage(first, new TextMessage("r|C"));
        handler.handleMessage(second, new TextMessage("r|M"));

        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(second, atLeastOnce()).sendMessage(captor.capture());
        String out = captor.getValue().getPayload();
        Frame decoded = PerceptionCodec.decode(out);

        assertThat(decoded).isInstanceOf(Frame.ErrorFrame.class);
        Frame.ErrorFrame err = (Frame.ErrorFrame) decoded;
        assertThat(err.code()).isEqualTo(429);
        assertThat(err.message()).contains("population cap exceeded");
    }

    private static WebSocketSession newSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
