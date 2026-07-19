package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.engine.TickEvent;
import com.paralife.world.GridConfig;
import com.paralife.world.WorldGrid;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

class ObserverBroadcasterTest {

    private WorldGrid worldGrid;
    private EnvironmentEngine env;
    private BotRegistry botRegistry;
    private SpeciesSpawnCounter spawnCounter;
    private ObserverOutboundSender sender;
    private ObserverBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        env = mock(EnvironmentEngine.class);
        when(env.snapshot()).thenReturn(new EnvironmentSnapshot(List.of(), List.of(), List.of()));
        botRegistry = new BotRegistry();
        spawnCounter = new SpeciesSpawnCounter();
        sender = mock(ObserverOutboundSender.class);
        broadcaster = new ObserverBroadcaster(new ObserverFrameBuilder(), worldGrid, env,
                botRegistry, spawnCounter, sender);
    }

    private static WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void zeroObserversProducesNoOfferAndNoError() {
        broadcaster.onTick(new TickEvent(1)); // must not throw
        verify(sender, times(0)).offer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void offersExactlyOneFrameToEachOpenObserver() {
        broadcaster.register(session("a"));
        broadcaster.register(session("b"));

        broadcaster.onTick(new TickEvent(7));

        verify(sender, times(1)).offer(org.mockito.ArgumentMatchers.eq("a"),
                org.mockito.ArgumentMatchers.anyString());
        verify(sender, times(1)).offer(org.mockito.ArgumentMatchers.eq("b"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void serializesExactlyOnceRegardlessOfObserverCount() {
        ObserverBroadcaster spy = spy(broadcaster);
        spy.register(session("a"));
        spy.register(session("b"));
        spy.register(session("c"));

        spy.onTick(new TickEvent(1));

        verify(spy, times(1)).serializeFrame(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allObserversReceiveTheSameNonEmptyPayload() {
        broadcaster.register(session("a"));
        broadcaster.register(session("b"));

        broadcaster.onTick(new TickEvent(1));

        ArgumentCaptor<String> payloadA = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadB = ArgumentCaptor.forClass(String.class);
        verify(sender).offer(org.mockito.ArgumentMatchers.eq("a"), payloadA.capture());
        verify(sender).offer(org.mockito.ArgumentMatchers.eq("b"), payloadB.capture());
        assertThat(payloadA.getValue()).as("precondition: non-empty payload").isNotEmpty();
        assertThat(payloadA.getValue()).isEqualTo(payloadB.getValue());
    }

    @Test
    void aThrowingCaptureIsContainedAndOnTickNeverEscapes() {
        // positive control: normal onTick with an observer does not throw (asserted above).
        // failure path: a throwing collaborator must be contained so the synchronous tick
        // publish is not aborted (later @Order listeners must still run).
        when(env.snapshot()).thenThrow(new RuntimeException("boom"));
        broadcaster.register(session("a"));

        assertThatCode(() -> broadcaster.onTick(new TickEvent(1)))
                .as("observer capture failure must not escape the tick listener")
                .doesNotThrowAnyException();
    }
}
