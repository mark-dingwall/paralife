package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
import com.paralife.websocket.Messages.CellView;
import com.paralife.websocket.Messages.EntityState;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;

/**
 * Sends each registered bot a Perception message each tick with their local neighbourhood.
 *
 * <p>Runs at Order(50) — after SimulationEngine(10) and ActionResolver(20),
 * before TickBroadcaster(100).
 *
 * <p>For composite members: builds a stitched perception from all SENSOR members,
 * memoized per composite per tick (D-19, D-20, D-36, T-12-12).
 */
@Component
public class PerceptionBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(PerceptionBroadcaster.class);

    /** Perception radius: 2 means a 5x5 grid (2 cells in each direction). */
    public static final int PERCEPTION_RADIUS = 2;

    private final BotRegistry botRegistry;
    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final ObjectMapper objectMapper;
    private final CompositeRegistry compositeRegistry;

    public PerceptionBroadcaster(BotRegistry botRegistry, SessionRegistry sessionRegistry,
                                  WorldGrid worldGrid, ObjectMapper objectMapper,
                                  CompositeRegistry compositeRegistry) {
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.objectMapper = objectMapper;
        this.compositeRegistry = compositeRegistry;
    }

    @EventListener
    @Order(50) // After SimulationEngine(10) + ActionResolver(20), before TickBroadcaster(100)
    public void onTick(TickEvent event) {
        var bots = botRegistry.getAllBots();
        if (bots.isEmpty()) return;

        // Memoize stitched perception per composite per tick (T-12-12)
        Map<String, Messages.CompositePerception> compositePerceptionCache = new HashMap<>();

        int sent = 0;
        int failed = 0;

        for (var bot : bots) {
            WebSocketSession session = sessionRegistry.getSession(bot.sessionId());
            if (session == null || !session.isOpen()) {
                continue;
            }

            try {
                Cell cell = worldGrid.getCell(bot.position().x(), bot.position().y());
                Messages msg;

                if (cell.occupant() instanceof Entity.CompositeMember cm) {
                    // Composite member — send stitched perception (D-19, D-36)
                    String compositeId = cm.compositeId();

                    // Build or retrieve cached stitched perception
                    if (!compositePerceptionCache.containsKey(compositeId)) {
                        var stitched = buildStitchedPerception(event.tickNumber(), cm, bot);
                        compositePerceptionCache.put(compositeId, stitched);
                    }

                    var cached = compositePerceptionCache.get(compositeId);
                    if (cached == null) {
                        // Blind composite (no SENSOR members, D-20) — skip
                        continue;
                    }

                    // Create per-member perception with correct self state and role
                    msg = new Messages.CompositePerception(
                            cached.tickNumber(),
                            buildMemberEntityState(cm, bot.position()),
                            cached.stitchedNeighbourhood(),
                            cached.compositeSize(),
                            cached.sharedPoolEnergy(),
                            cached.maxPoolEnergy(),
                            cm.role().name()
                    );
                } else {
                    // Solo entity — existing path
                    msg = buildPerception(event.tickNumber(), bot);
                }

                String json = objectMapper.writeValueAsString(msg);
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
     * Build stitched perception for a composite. Returns null if the composite
     * has no SENSOR members (blind composite, D-20).
     */
    private Messages.CompositePerception buildStitchedPerception(long tickNumber,
            Entity.CompositeMember member, BotRegistry.BotState bot) {
        var compositeOpt = compositeRegistry.getComposite(member.compositeId());
        if (compositeOpt.isEmpty()) return null;

        var composite = compositeOpt.get();

        // Build stitched coverage from SENSOR members (D-19)
        Set<Position> coverage = stitchSensorCoverage(composite);

        if (coverage.isEmpty()) {
            // Blind composite (D-20) — no perception sent
            return null;
        }

        // Convert coverage to CellView grid
        List<List<CellView>> neighbourhood = buildNeighbourhoodFromCoverage(coverage);

        return new Messages.CompositePerception(
                tickNumber,
                buildMemberEntityState(member, bot.position()),
                neighbourhood,
                composite.getMemberIds().size(),
                composite.getSharedPoolEnergy(),
                composite.getMaxPoolEnergy(),
                member.role().name()
        );
    }

    /**
     * Build the union of all SENSOR member 5x5 perception circles.
     * Non-SENSOR members contribute no vision (D-21).
     * Positions are deduplicated via HashSet.
     *
     * <p>Package-private for testing.
     */
    Set<Position> stitchSensorCoverage(CompositeRegistry.CompositeState composite) {
        Set<Position> coverage = new HashSet<>();
        int gridWidth = worldGrid.getWidth();
        int gridHeight = worldGrid.getHeight();

        for (String memberId : composite.getMemberIds()) {
            Position pos = composite.getPositionForMember(memberId);
            if (pos == null) continue;

            Cell cell = worldGrid.getCell(pos.x(), pos.y());
            if (cell.occupant() instanceof Entity.CompositeMember cm
                    && cm.role() == Entity.Role.SENSOR) {
                // Add 5x5 circle around this SENSOR
                for (int dy = -PERCEPTION_RADIUS; dy <= PERCEPTION_RADIUS; dy++) {
                    for (int dx = -PERCEPTION_RADIUS; dx <= PERCEPTION_RADIUS; dx++) {
                        coverage.add(Position.wrap(pos.x() + dx, pos.y() + dy,
                                gridWidth, gridHeight));
                    }
                }
            }
        }
        return coverage;
    }

    /**
     * Convert a set of covered positions into a sorted grid of CellViews.
     * Positions are sorted by (y, x) to produce a row-major rectangular grid.
     * Only cells within coverage are resolved; gaps in the bounding box get fog-of-war views.
     */
    private List<List<CellView>> buildNeighbourhoodFromCoverage(Set<Position> coverage) {
        // Sort positions to find bounding dimensions and produce consistent output
        List<Position> sorted = new ArrayList<>(coverage);
        sorted.sort(Comparator.comparingInt(Position::y).thenComparingInt(Position::x));

        // Group by y-coordinate to form rows
        Map<Integer, List<Position>> byRow = new LinkedHashMap<>();
        for (Position pos : sorted) {
            byRow.computeIfAbsent(pos.y(), k -> new ArrayList<>()).add(pos);
        }

        List<List<CellView>> neighbourhood = new ArrayList<>();
        for (var entry : byRow.entrySet()) {
            List<CellView> row = new ArrayList<>();
            for (Position pos : entry.getValue()) {
                Cell cell = worldGrid.getCell(pos.x(), pos.y());
                row.add(cellToView(cell));
            }
            neighbourhood.add(row);
        }

        return neighbourhood;
    }

    /**
     * Build an EntityState for a composite member.
     */
    private EntityState buildMemberEntityState(Entity.CompositeMember cm, Position pos) {
        return new EntityState(cm.id(), cm.type().name(), cm.energy(), cm.maxEnergy(),
                pos.x(), pos.y());
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
            case Entity.CompositeMember cm -> new CellView(
                    "COMPOSITE_" + cm.type() + "_" + cm.role(),
                    cm.id(), cell.nutrientLevel());
        };
    }
}
