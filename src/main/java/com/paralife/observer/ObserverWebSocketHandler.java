package com.paralife.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Read-only {@code /ws/observer} handler. No admission FSM, no vision-scoping, no
 * resume/stall. On open it follows the bootstrap-barrier: attach the outbound sender
 * → send the bootstrap frame under {@code synchronized(session)} → only THEN register
 * with the broadcaster (so no world frame can precede or overwrite the bootstrap).
 * Inbound frames are ignored (AbstractWebSocketHandler defaults are no-ops).
 */
@Component
public class ObserverWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ObserverWebSocketHandler.class);

    private final ObserverBroadcaster broadcaster;
    private final ObserverOutboundSender sender;
    private final ObserverSessionGate gate;
    private final ObserverFrameBuilder builder;
    private final WorldGrid worldGrid;
    private final ObjectMapper mapper = new ObjectMapper();

    public ObserverWebSocketHandler(ObserverBroadcaster broadcaster, ObserverOutboundSender sender,
                                    ObserverSessionGate gate, ObserverFrameBuilder builder,
                                    WorldGrid worldGrid) {
        this.broadcaster = broadcaster;
        this.sender = sender;
        this.gate = gate;
        this.builder = builder;
        this.worldGrid = worldGrid;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sender.attach(session);
        // Bootstrap-barrier: send static terrain BEFORE the broadcaster can offer a world frame.
        ObserverFrame.BootstrapFrame boot = builder.buildBootstrap(worldGrid.snapshot());
        String payload = mapper.writeValueAsString(boot);
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
        broadcaster.register(session); // now eligible for world frames
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Observer transport error for session {}: {}", session.getId(), exception.getMessage());
        cleanup(session);
    }

    private void cleanup(WebSocketSession session) {
        broadcaster.unregister(session);
        sender.detach(session);
        gate.releaseIfHeld(session); // release-once (O9)
    }
}
