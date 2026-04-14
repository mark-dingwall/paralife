package com.paralife.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.engine.CompositeRegistry;
import com.paralife.engine.SeasonTracker;
import com.paralife.engine.SeasonsConfig;
import com.paralife.engine.SimulationEngine;
import com.paralife.engine.TickEvent;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TickBroadcasterTest {

    private SessionRegistry sessionRegistry;
    private WorldGrid worldGrid;
    private ObjectMapper objectMapper;
    private SimulationEngine simulationEngine;
    private CompositeRegistry compositeRegistry;
    private SeasonTracker seasonTracker;
    private TickBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        sessionRegistry = new SessionRegistry();
        worldGrid = new WorldGrid(new GridConfig(8, 8));
        objectMapper = new ObjectMapper();
        simulationEngine = mock(SimulationEngine.class);
        compositeRegistry = new CompositeRegistry();
        // yearLength=200, amplitude=0.5 — tick=0 is SPRING peak (multiplier=1.5).
        seasonTracker = new SeasonTracker(new SeasonsConfig(200, 0.5));
        when(simulationEngine.getLastTickBondCount()).thenReturn(0);
        broadcaster = new TickBroadcaster(sessionRegistry, worldGrid, objectMapper,
                simulationEngine, compositeRegistry, seasonTracker);
    }

    @Test
    void onTickBroadcastsToAllSessions() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void onTickSkipsWhenNoSessions() {
        // Should not throw when no sessions registered
        broadcaster.onTick(new TickEvent(1));
    }

    @Test
    void onTickSkipsClosedSessions() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(false);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void tickMessageContainsExpectedFields() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(42));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(json.get("type").asText()).isEqualTo("tick");
        assertThat(json.get("tickNumber").asLong()).isEqualTo(42);
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.has("entityCount")).isTrue();
        assertThat(json.has("bondCount")).isTrue();
        assertThat(json.has("compositeCount")).isTrue();
        assertThat(json.has("seasonPhase")).isTrue();
        assertThat(json.has("seasonalMultiplier")).isTrue();
    }

    @Test
    void tickMessageIncludesSpringAtTickZero() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(0));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        // Tick 0, amplitude 0.5 → multiplier = 1 + 0.5 * sin(0) = 1.0 (mid-SPRING)
        assertThat(json.get("seasonPhase").asText()).isEqualTo("SPRING");
        assertThat(json.get("seasonalMultiplier").asDouble()).isEqualTo(1.0);
    }

    @Test
    void tickMessageReportsWinterTroughAtThreeQuarterYear() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(150));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        // Tick 150 of 200 → sin(3PI/2) = -1 → multiplier = 1 - 0.5 = 0.5 (winter trough)
        assertThat(json.get("seasonPhase").asText()).isEqualTo("WINTER");
        assertThat(json.get("seasonalMultiplier").asDouble()).isEqualTo(0.5);
    }

    @Test
    void tickMessageIncludesBondCount() throws Exception {
        when(simulationEngine.getLastTickBondCount()).thenReturn(3);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(json.get("bondCount").asInt()).isEqualTo(3);
    }

    @Test
    void tickMessageBondCountZeroByDefault() throws Exception {
        // simulationEngine returns 0 by default (setUp)

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(json.get("bondCount").asInt()).isEqualTo(0);
    }

    @Test
    void tickMessageIncludesCompositeCount() throws Exception {
        // Register a composite
        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0)),
                100, 200);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(json.get("compositeCount").asInt()).isEqualTo(1);
    }
}
