package com.paralife.observer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.engine.TickEvent;
import com.paralife.world.WorldGrid;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tick-{@code @Order(60)} listener (after TickBroadcaster @Order(50)). On the tick
 * thread it does BOUNDED work only: capture one immutable grid + env snapshot + owned
 * set + spawn counts, serialize ONCE to JSON, then non-blocking {@code offer} the
 * shared payload to each observer's latest-wins mailbox. It NEVER calls
 * {@code session.sendMessage} here — a blocked socket must not add latency to tick work.
 */
@Component
public class ObserverBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ObserverBroadcaster.class);

    private final Set<WebSocketSession> observers = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObserverFrameBuilder builder;
    private final WorldGrid worldGrid;
    private final EnvironmentEngine environmentEngine;
    private final BotRegistry botRegistry;
    private final SpeciesSpawnCounter spawnCounter;
    private final ObserverOutboundSender sender;

    public ObserverBroadcaster(ObserverFrameBuilder builder, WorldGrid worldGrid,
                               EnvironmentEngine environmentEngine, BotRegistry botRegistry,
                               SpeciesSpawnCounter spawnCounter, ObserverOutboundSender sender) {
        this.builder = builder;
        this.worldGrid = worldGrid;
        this.environmentEngine = environmentEngine;
        this.botRegistry = botRegistry;
        this.spawnCounter = spawnCounter;
        this.sender = sender;
    }

    public void register(WebSocketSession session) {
        observers.add(session);
    }

    public void unregister(WebSocketSession session) {
        observers.remove(session);
    }

    public int observerCount() {
        return observers.size();
    }

    @EventListener
    @Order(60) // after TickBroadcaster(50); bounded on-thread work only
    public void onTick(TickEvent event) {
        if (observers.isEmpty()) return; // cheap early-out
        try {
            WorldGrid.GridSnapshot grid = worldGrid.snapshot();
            EnvironmentSnapshot env = environmentEngine.snapshot();
            Set<String> owned = botRegistry.ownedEntityIds();
            long[] spawns = spawnCounter.snapshot();

            ObserverFrame.WorldFrame frame = builder.buildWorld(event.tickNumber(), grid, env, owned, spawns);
            String payload = serializeFrame(frame);
            if (payload == null) return;

            for (WebSocketSession s : observers) {
                try {
                    sender.offer(s.getId(), payload);
                } catch (RuntimeException e) {
                    // one bad session must not abort the fan-out
                    log.warn("Observer offer failed for session={}: {}", s.getId(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            // TickEvent is published SYNCHRONOUSLY on the tick thread (TickEngine.java:114). An
            // exception escaping here would abort publishEvent and skip every later listener for
            // this tick — WebSocketKeepaliveService @Order(200), TickHealthMonitor @Order(MAX).
            // The observer is best-effort: contain any capture/build/serialize failure here.
            log.warn("Observer broadcast failed at tick {} (contained): {}",
                    event.tickNumber(), e.getMessage());
        }
    }

    /** Single serialization seam (O2a): called exactly once per tick regardless of observer count. */
    String serializeFrame(ObserverFrame.WorldFrame frame) {
        try {
            return mapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.warn("Observer frame serialization failed at tick {}: {}", frame.tick(), e.getMessage());
            return null;
        }
    }
}
