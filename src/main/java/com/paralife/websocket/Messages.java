package com.paralife.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base class for all WebSocket messages.
 * Uses JSON type discrimination via "type" field.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Messages.Welcome.class, name = "welcome"),
        @JsonSubTypes.Type(value = Messages.Tick.class, name = "tick"),
        @JsonSubTypes.Type(value = Messages.Register.class, name = "register"),
        @JsonSubTypes.Type(value = Messages.Registered.class, name = "registered"),
        @JsonSubTypes.Type(value = Messages.Heartbeat.class, name = "heartbeat"),
        @JsonSubTypes.Type(value = Messages.Error.class, name = "error"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface Messages {

    /**
     * Sent to client immediately on WebSocket connection.
     */
    record Welcome(
            String sessionId,
            int worldWidth,
            int worldHeight,
            long currentTick
    ) implements Messages {}

    /**
     * Broadcast to all clients each tick.
     */
    record Tick(
            long tickNumber,
            long timestamp,
            int entityCount
    ) implements Messages {}

    /**
     * Client → Server: register as an entity in the world.
     */
    record Register(
            String entityType
    ) implements Messages {}

    /**
     * Server → Client: registration confirmed.
     */
    record Registered(
            String entityId,
            int x,
            int y
    ) implements Messages {}

    /**
     * Client → Server: keep-alive.
     */
    record Heartbeat() implements Messages {}

    /**
     * Server → Client: error message.
     */
    record Error(
            String code,
            String message
    ) implements Messages {}
}
