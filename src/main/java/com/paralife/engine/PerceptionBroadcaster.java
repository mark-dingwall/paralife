package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
import com.paralife.websocket.Messages.CellView;
import com.paralife.websocket.Messages.EntityState;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends each registered bot a Perception message each tick with their local neighbourhood.
 *
 * <p>Runs at Order(50) — after SimulationEngine(10) and ActionResolver(20),
 * before TickBroadcaster(100).
 */
@Component
public class PerceptionBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(PerceptionBroadcaster.class);

    /** Perception radius: 2 means a 5×5 grid (2 cells in each direction). */
    public static final int PERCEPTION_RADIUS = 2;

    private final BotRegistry botRegistry;
    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final ObjectMapper objectMapper;

    public PerceptionBroadcaster(BotRegistry botRegistry, SessionRegistry sessionRegistry,
                                  WorldGrid worldGrid, ObjectMapper objectMapper) {
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.objectMapper = objectMapper;
    }

    @EventListener
    @Order(50) // After SimulationEngine(10) + ActionResolver(20), before TickBroadcaster(100)
    public void onTick(TickEvent event) {
        var bots = botRegistry.getAllBots();
        if (bots.isEmpty()) return;

        int sent = 0;
        int failed = 0;

        for (var bot : bots) {
            WebSocketSession session = sessionRegistry.getSession(bot.sessionId());
            if (session == null || !session.isOpen()) {
                continue;
            }

            try {
                Messages.Perception perception = buildPerception(event.tickNumber(), bot);
                String json = objectMapper.writeValueAsString(perception);
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
                sent++;
            } catch (IOException e) {
                failed++;
                log.warn("Failed to send perception to session {}: {}", bot.sessionId(), e.getMessage());
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Tick {} perception: sent={}, failed={}, bots={}",
                    event.tickNumber(), sent, failed, bots.size());
        }
    }

    /**
     * Build a Perception message for a bot at its current position.
     */
    Messages.Perception buildPerception(long tickNumber, BotRegistry.BotState bot) {
        var pos = bot.position();

        // Build entity state from the cell at the bot's position
        Cell selfCell = worldGrid.getCell(pos.x(), pos.y());
        EntityState selfState;
        if (selfCell.occupant() instanceof Particle p) {
            selfState = new EntityState(
                    p.id(), p.type().name(), p.energy(), p.maxEnergy(),
                    pos.x(), pos.y()
            );
        } else {
            // Entity died or was displaced — send last known position with 0 energy
            selfState = new EntityState(
                    bot.entityId(), "UNKNOWN", 0, 0,
                    pos.x(), pos.y()
            );
        }

        // Build neighbourhood grid
        int diameter = PERCEPTION_RADIUS * 2 + 1;
        List<List<CellView>> neighbourhood = new ArrayList<>(diameter);

        for (int dy = -PERCEPTION_RADIUS; dy <= PERCEPTION_RADIUS; dy++) {
            List<CellView> row = new ArrayList<>(diameter);
            for (int dx = -PERCEPTION_RADIUS; dx <= PERCEPTION_RADIUS; dx++) {
                Cell cell = worldGrid.getCell(pos.x() + dx, pos.y() + dy);
                row.add(cellToView(cell));
            }
            neighbourhood.add(row);
        }

        return new Messages.Perception(tickNumber, selfState, neighbourhood, PERCEPTION_RADIUS);
    }

    /**
     * Convert a Cell to a compact CellView for the perception message.
     */
    static CellView cellToView(Cell cell) {
        if (cell.isEmpty()) {
            return new CellView(null, null, cell.nutrientLevel());
        }
        Entity occupant = cell.occupant();
        return switch (occupant) {
            case Particle p -> new CellView(p.type().name(), p.id(), cell.nutrientLevel());
            case Entity.Rock r -> new CellView("ROCK", r.id(), cell.nutrientLevel());
            case Entity.Nutrient n -> new CellView("NUTRIENT", n.id(), cell.nutrientLevel());
            case Entity.BondedPair bp -> new CellView(
                    "BONDED_" + bp.primaryType() + "_" + bp.secondaryType(),
                    bp.id(), cell.nutrientLevel());
        };
    }
}
