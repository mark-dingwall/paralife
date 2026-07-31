package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.engine.TickEvent;
import com.paralife.world.GridConfig;
import com.paralife.world.WorldGrid;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * O8 bootstrap-barrier. The invariant is a happens-before: the bootstrap is SENT before the
 * observer is REGISTERED with the broadcaster (registration is what makes it eligible for world
 * frames). We record every session send and the register() call into one ordered list and assert
 * bootstrap-send precedes register — so no world frame can be offered, let alone delivered, before
 * bootstrap. Bootstrap is sent directly under synchronized(session), never via the latest-wins
 * slot, so a concurrent tick cannot overwrite it. Deterministic — no flaky real-thread race, and
 * (unlike a "call the method then fire a tick" test) it actually fails when register is reordered
 * before the bootstrap send.
 */
class ObserverBootstrapOrderingTest {

    private static String sendType(WebSocketMessage<?> m) {
        String p = ((TextMessage) m).getPayload();
        return p.contains("\"type\":\"bootstrap\"") ? "send:bootstrap"
             : p.contains("\"type\":\"world\"") ? "send:world" : "send:other";
    }

    @Test
    void bootstrapIsSentBeforeTheObserverIsRegistered_andWorldNeverPrecedesIt() throws Exception {
        WorldGrid grid = new WorldGrid(new GridConfig(16, 16));
        EnvironmentEngine env = mock(EnvironmentEngine.class);
        when(env.snapshot()).thenReturn(new EnvironmentSnapshot(List.of(), List.of(), List.of(), Set.of()));
        ObserverFrameBuilder builder = new ObserverFrameBuilder();
        ObserverOutboundSender sender = new ObserverOutboundSender();
        ObserverBroadcaster broadcaster = spy(new ObserverBroadcaster(builder, grid, env,
                new BotRegistry(), new SpeciesSpawnCounter(), sender));
        ObserverWebSocketHandler handler = new ObserverWebSocketHandler(
                broadcaster, sender, new ObserverSessionGate(new ObserverConfig(true, 4)),
                builder, grid);

        List<String> events = new CopyOnWriteArrayList<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("obs");
        when(session.isOpen()).thenReturn(true);
        doAnswer(inv -> { events.add(sendType(inv.getArgument(0))); return null; })
                .when(session).sendMessage(org.mockito.ArgumentMatchers.any());
        // record the register() call in the SAME ordered list, then run the real registration
        doAnswer(inv -> { events.add("register"); return inv.callRealMethod(); })
                .when(broadcaster).register(org.mockito.ArgumentMatchers.any());

        handler.afterConnectionEstablished(session);

        assertThat(events)
                .as("barrier order: bootstrap is SENT, THEN the observer is registered")
                .containsExactly("send:bootstrap", "register");

        // end-to-end: only after registration does a tick's world frame reach the wire (second)
        broadcaster.onTick(new TickEvent(1));
        Thread.sleep(200); // let the drain VT flush the world frame
        assertThat(events)
                .as("world frame follows — never precedes — bootstrap")
                .containsExactly("send:bootstrap", "register", "send:world");

        sender.detach("obs");
    }
}
