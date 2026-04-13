package com.paralife.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Base class for all WebSocket messages.
 * Uses JSON type discrimination via "type" field.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        // Server → Client
        @JsonSubTypes.Type(value = Messages.Welcome.class, name = "welcome"),
        @JsonSubTypes.Type(value = Messages.Tick.class, name = "tick"),
        @JsonSubTypes.Type(value = Messages.Registered.class, name = "registered"),
        @JsonSubTypes.Type(value = Messages.Error.class, name = "error"),
        @JsonSubTypes.Type(value = Messages.Perception.class, name = "perception"),
        @JsonSubTypes.Type(value = Messages.ActionResult.class, name = "action_result"),
        // Client → Server
        @JsonSubTypes.Type(value = Messages.Register.class, name = "register"),
        @JsonSubTypes.Type(value = Messages.Heartbeat.class, name = "heartbeat"),
        @JsonSubTypes.Type(value = Messages.Action.class, name = "action"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface Messages {

    // ── Server → Client ───────────────────────────────────────────

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
            int entityCount,
            int bondCount
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
     * Server → Client: error message.
     */
    record Error(
            String code,
            String message
    ) implements Messages {}

    /**
     * Server → Client: per-tick perception for a registered bot.
     * Contains the bot's own state and the local neighbourhood around it.
     */
    record Perception(
            long tickNumber,
            /** The bot's entity state. */
            EntityState self,
            /** Local neighbourhood cells, row-major from top-left. */
            List<List<CellView>> neighbourhood,
            /** Neighbourhood radius (e.g. 2 means a 5×5 grid centred on the entity). */
            int radius
    ) implements Messages {}

    /**
     * Server → Client: result of a submitted action.
     */
    record ActionResult(
            long tickNumber,
            boolean success,
            String actionType,
            String reason
    ) implements Messages {}

    // ── Client → Server ───────────────────────────────────────────

    /**
     * Client → Server: register as an entity in the world.
     */
    record Register(
            String entityType
    ) implements Messages {}

    /**
     * Client → Server: keep-alive.
     */
    record Heartbeat() implements Messages {}

    /**
     * Client → Server: submit an action for the current tick.
     * actionType: "move", "consume", "reproduce", "rest"
     * direction: for move/reproduce — "N", "NE", "E", "SE", "S", "SW", "W", "NW"
     */
    record Action(
            String actionType,
            String direction
    ) implements Messages {}

    // ── Shared view types ─────────────────────────────────────────

    /**
     * Compact view of an entity's own state, sent inside Perception.
     */
    record EntityState(
            String entityId,
            String particleType,
            int energy,
            int maxEnergy,
            int x,
            int y
    ) {}

    /**
     * Compact view of a single cell in the neighbourhood.
     * Null occupantType means empty cell. "rock" / "nutrient" / particle type name for occupied.
     */
    record CellView(
            String occupantType,
            String occupantId,
            int nutrientLevel
    ) {}
}
