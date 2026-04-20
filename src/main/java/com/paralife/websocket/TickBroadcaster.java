package com.paralife.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.CompositeRegistry;
import com.paralife.engine.EntityIds;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.SimulationConfig;
import com.paralife.engine.TickEvent;
import com.paralife.websocket.Messages.CellView;
import com.paralife.websocket.Messages.EntityState;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><b>Phase 14 Plan 05</b> additions:
 * <ul>
 *   <li>Dynamic SOLO radius: 7x7 when the bot's entity has {@code SENSOR_PLUS_1}
 *       (Particle.id() or bp.id() — bot.entityId() returns the correct id by
 *       construction, cycle-9 action C.1).</li>
 *   <li>Dynamic COMPOSITE SENSOR per-member stitched radius: 7x7 for each SENSOR
 *       member that carries {@code SENSOR_PLUS_1} (cycle-4 action item #8).</li>
 *   <li>Vision-scoped OVERCROWDED bit (D-40) per bot — computed from the
 *       Moore-neighbourhood count the bot can see, compared against the LIVE
 *       {@code SimulationConfig.overcrowdingThreshold()}.</li>
 *   <li>Per-bot overcrowded-bit <b>recomposition</b> (cycle-6 MEDIUM #9): the
 *       env cache provides a cell-level cellStatus byte whose OVERCROWDED bit
 *       reflects the SERVER's global count. For each bot we MUST mask bit 0 out
 *       of the cached value and OR in the per-bot vision-scoped bit — verbatim
 *       expression: {@code cellStatus = (cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit}.</li>
 *   <li>6-arg CellView: carries {@code cellStatus} + {@code entityStatus} bytes
 *       alongside the legacy {@code flags}.</li>
 * </ul>
 */
@Component
public class TickBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TickBroadcaster.class);

    /** Perception radius: 2 means a 5x5 grid (2 cells in each direction). */
    public static final int PERCEPTION_RADIUS = 2;

    /**
     * cycle-6 MEDIUM #9: mask for the vision-scoped OVERCROWDED bit in
     * {@code cellStatus}. The broadcaster strips bit 0 from the cached env
     * cellStatus byte and OR's in the per-bot computed overcrowded bit so the
     * view delivered to each bot reflects THAT bot's visible neighbourhood
     * rather than the global server count (D-40).
     */
    static final byte BIT_OVERCROWDED = 0x01;

    private final BotRegistry botRegistry;
    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final ObjectMapper objectMapper;
    private final CompositeRegistry compositeRegistry;

    /**
     * Phase 14 Plan 05 collaborators. Optional for pre-Phase-14 tests that
     * construct this bean via the 5-arg ctor with no env pipeline wired.
     */
    private EnvironmentEngine environmentEngine;
    private BuffRegistry buffRegistry;
    private SimulationConfig simulationConfig;

    /**
     * Primary {@code @Autowired} ctor for Spring production wiring (Plan 14-05).
     * Injects the env/buff/config collaborators needed for the 6-arg CellView
     * pipeline and vision-scoped overcrowding.
     */
    @Autowired
    public TickBroadcaster(BotRegistry botRegistry, SessionRegistry sessionRegistry,
                                  WorldGrid worldGrid, ObjectMapper objectMapper,
                                  CompositeRegistry compositeRegistry,
                                  EnvironmentEngine environmentEngine,
                                  BuffRegistry buffRegistry,
                                  SimulationConfig simulationConfig) {
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.objectMapper = objectMapper;
        this.compositeRegistry = compositeRegistry;
        this.environmentEngine = environmentEngine;
        this.buffRegistry = buffRegistry;
        this.simulationConfig = simulationConfig;
    }

    /**
     * Back-compat 5-arg ctor for pre-Phase-14 unit tests that don't wire the
     * env pipeline. Wires a fresh empty {@link BuffRegistry},
     * {@link SimulationConfig#defaults()}, and a null environmentEngine
     * (status-cache reads treat null as "no env effects"). Every env call
     * site is null-guarded.
     */
    public TickBroadcaster(BotRegistry botRegistry, SessionRegistry sessionRegistry,
                                  WorldGrid worldGrid, ObjectMapper objectMapper,
                                  CompositeRegistry compositeRegistry) {
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.objectMapper = objectMapper;
        this.compositeRegistry = compositeRegistry;
        this.environmentEngine = null;
        this.buffRegistry = new BuffRegistry();
        this.simulationConfig = SimulationConfig.defaults();
    }

    @EventListener
    @Order(50) // After SimulationEngine(10) + ActionResolver(20) — tick-pipeline perception step
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
     *
     * <p>Plan 14-05 cycle-6 HIGH #3: SENSOR_PLUS_1 on a BondedPair extends this
     * solo path too — {@code bot.entityId()} is Particle.id() for Particle-bound
     * bots and bp.id() for BondedPair-bound bots by construction, so one code
     * path covers both. cycle-9 action C.1: use {@code bot.entityId()} directly
     * (no {@code bot.entity()} accessor exists on {@link BotRegistry.BotState}).
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

        // Plan 14-05: SENSOR_PLUS_1 expands radius 2 -> 3 (5x5 -> 7x7) for SOLO
        // bots (Particle + BondedPair per cycle-6 HIGH #3). bot.entityId()
        // returns Particle.id() for Particle-bound bots and bp.id() for
        // BondedPair-bound bots (cycle-9 action C.1 — no bot.entity() API).
        String botEntityId = bot.entityId();
        int radius = (botEntityId != null
                && buffRegistry.hasBuff(botEntityId, BuffRegistry.BuffType.SENSOR_PLUS_1))
                ? 3
                : PERCEPTION_RADIUS;

        int diameter = radius * 2 + 1;
        List<List<CellView>> neighbourhood = new ArrayList<>(diameter);

        for (int dy = -radius; dy <= radius; dy++) {
            List<CellView> row = new ArrayList<>(diameter);
            for (int dx = -radius; dx <= radius; dx++) {
                int cx = pos.x() + dx;
                int cy = pos.y() + dy;
                row.add(cellToView(cx, cy, pos, radius));
            }
            neighbourhood.add(row);
        }

        return new Messages.Perception(tickNumber, selfState, neighbourhood, radius);
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
        List<List<CellView>> neighbourhood = buildNeighbourhoodFromCoverage(coverage, bot.position());

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
     * <p><b>Plan 14-05 cycle-4 action item #8 (Codex MEDIUM):</b> each SENSOR
     * member's coverage circle is sized per-member — radius 3 when that member
     * carries {@code SENSOR_PLUS_1}, else {@link #PERCEPTION_RADIUS}. Without
     * this the composite SENSOR buff would be dead-letter — solo bots would
     * see 7x7 while composite SENSORs still stitched with fixed 5x5 circles.
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
                // cycle-4 action item #8: per-member radius — SENSOR_PLUS_1 expands
                // this SENSOR's coverage circle from 5x5 to 7x7.
                int memberRadius = buffRegistry.hasBuff(memberId, BuffRegistry.BuffType.SENSOR_PLUS_1)
                        ? 3
                        : PERCEPTION_RADIUS;
                for (int dy = -memberRadius; dy <= memberRadius; dy++) {
                    for (int dx = -memberRadius; dx <= memberRadius; dx++) {
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
     *
     * <p>Plan 14-05: per-cell status byte needs a vision-scoped OVERCROWDED
     * recomposition — {@code botPos} is passed through so
     * {@link #cellToView(int, int, Position, int)} can compute the bot-scoped
     * bit relative to the composite member that the message is addressed to.
     */
    private List<List<CellView>> buildNeighbourhoodFromCoverage(Set<Position> coverage, Position botPos) {
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
                row.add(cellToView(pos.x(), pos.y(), botPos, PERCEPTION_RADIUS));
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
     * Plan 14-05: per-bot cellToView.
     *
     * <p>Reads {@code environmentEngine.getCellStatus(pos)} for the cached
     * env cell-status byte, then performs the cycle-6 MEDIUM #9 verbatim
     * mask-and-OR: {@code cellStatus = (cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit}.
     * The per-bot overcrowded bit is computed via
     * {@link #computeVisionScopedOvercrowded} against the LIVE
     * {@link SimulationConfig#overcrowdingThreshold()} — yaml overrides take
     * effect without recompile.
     *
     * <p>{@code entityStatus} comes unchanged from the env cache keyed by
     * occupant id; rocks and nutrients have id {@code null} and emit 0.
     *
     * <p>Package-private for testing via {@link #cellToViewForTest}.
     */
    CellView cellToView(int x, int y, Position botPos, int radius) {
        int gridW = worldGrid.getWidth();
        int gridH = worldGrid.getHeight();
        int wrappedX = Math.floorMod(x, gridW);
        int wrappedY = Math.floorMod(y, gridH);
        Cell cell = worldGrid.getCell(wrappedX, wrappedY);
        Position cellPos = new Position(wrappedX, wrappedY);

        // cycle-6 MEDIUM #9: start with cached env cellStatus, STRIP the global
        // OVERCROWDED bit, then OR in the per-bot vision-scoped value.
        byte cached = environmentEngine != null ? environmentEngine.getCellStatus(cellPos) : (byte) 0;
        byte perBotOvercrowdedBit = computeVisionScopedOvercrowded(
                worldGrid, cellPos, botPos, radius, simulationConfig.overcrowdingThreshold())
                ? BIT_OVERCROWDED : 0x00;
        byte cellStatus = (byte) ((cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit);

        String occupantId = EntityIds.entityIdOf(cell.occupant());
        byte entityStatus = (occupantId == null || environmentEngine == null)
                ? (byte) 0
                : environmentEngine.getEntityStatus(occupantId);

        int flags = cell.flags();
        if (cell.isEmpty()) {
            return new CellView(null, null, cell.nutrientLevel(), flags, cellStatus, entityStatus);
        }
        Entity occupant = cell.occupant();
        String occupantType = typeCodeFor(occupant);
        // Match legacy occupantId behavior (null for empty, id otherwise). Rocks + Nutrients
        // produce null from EntityIds.entityIdOf, so the raw occupant-id-or-fallback path is used.
        String displayId = switch (occupant) {
            case Particle p -> p.id();
            case Entity.Rock r -> r.id();
            case Entity.Nutrient n -> n.id();
            case Entity.BondedPair bp -> bp.id();
            case Entity.CompositeMember cm -> cm.id();
        };
        return new CellView(occupantType, displayId, cell.nutrientLevel(), flags, cellStatus, entityStatus);
    }

    /** Test seam (package-private) — exposes the per-bot cellToView directly. */
    CellView cellToViewForTest(int x, int y, Position botPos, int radius) {
        return cellToView(x, y, botPos, radius);
    }

    private static String typeCodeFor(Entity occupant) {
        return switch (occupant) {
            case Particle p -> p.type().name();
            case Entity.Rock r -> "ROCK";
            case Entity.Nutrient n -> "NUTRIENT";
            case Entity.BondedPair bp -> "BONDED_" + bp.primaryType() + "_" + bp.secondaryType();
            case Entity.CompositeMember cm -> "COMPOSITE_" + cm.type() + "_" + cm.role();
        };
    }

    /**
     * Vision-scoped overcrowding (D-40).
     *
     * <p>Returns {@code true} when the {@code cellPos}'s Moore-neighbourhood
     * count of occupied cells (Particle + BondedPair only, matching
     * {@link SimulationEngine#processOvercrowding}) is at-or-above
     * {@code threshold}, restricted to neighbours the bot at {@code botPos}
     * can observe within {@code radius}. Neighbours outside the bot's
     * vision are UNKNOWN — this is the locked incomplete-information design
     * (cells at the vision edge may appear NOT overcrowded to the bot even
     * when the server counts them as overcrowded globally).
     *
     * <p>Package-private and {@code static} so {@link VisionScopedOvercrowdingTest}
     * can drive the predicate directly without a full Spring context.
     */
    static boolean computeVisionScopedOvercrowded(WorldGrid worldGrid, Position cellPos,
                                                    Position botPos, int radius, int threshold) {
        if (threshold <= 0 || threshold > 8) return false;
        int gridW = worldGrid.getWidth();
        int gridH = worldGrid.getHeight();

        int neighborCount = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = Math.floorMod(cellPos.x() + dx, gridW);
                int ny = Math.floorMod(cellPos.y() + dy, gridH);
                if (!isPositionVisible(nx, ny, botPos, radius, gridW, gridH)) continue;
                Entity occ = worldGrid.getCell(nx, ny).occupant();
                if (occ instanceof Particle || occ instanceof Entity.BondedPair) {
                    neighborCount++;
                }
            }
        }
        return neighborCount >= threshold;
    }

    /**
     * Toroidal visibility check — is (x, y) inside the bot's vision square
     * (Chebyshev radius) centred on {@code botPos}?
     */
    private static boolean isPositionVisible(int x, int y, Position botPos, int radius,
                                              int gridW, int gridH) {
        // Minimum toroidal distance along each axis
        int dx = Math.min(Math.floorMod(x - botPos.x(), gridW), Math.floorMod(botPos.x() - x, gridW));
        int dy = Math.min(Math.floorMod(y - botPos.y(), gridH), Math.floorMod(botPos.y() - y, gridH));
        return Math.max(dx, dy) <= radius;
    }

    /**
     * Convert a Cell to a compact CellView for the perception message.
     *
     * <p><b>Back-compat 1-arg (Cell) overload</b>: used by pre-Plan-14-05
     * {@code TickBroadcasterProjectionTest} static calls. Emits the legacy 4-arg
     * CellView constructor (zero statuses). New code MUST use the 4-arg
     * {@link #cellToView(int, int, Position, int)} per-bot overload so
     * vision-scoped overcrowding and env status bits are populated correctly.
     */
    static CellView cellToView(Cell cell) {
        int flags = cell.flags();
        if (cell.isEmpty()) {
            return new CellView(null, null, cell.nutrientLevel(), flags);
        }
        Entity occupant = cell.occupant();
        return switch (occupant) {
            case Particle p -> new CellView(p.type().name(), p.id(), cell.nutrientLevel(), flags);
            case Entity.Rock r -> new CellView("ROCK", r.id(), cell.nutrientLevel(), flags);
            case Entity.Nutrient n -> new CellView("NUTRIENT", n.id(), cell.nutrientLevel(), flags);
            case Entity.BondedPair bp -> new CellView(
                    "BONDED_" + bp.primaryType() + "_" + bp.secondaryType(),
                    bp.id(), cell.nutrientLevel(), flags);
            case Entity.CompositeMember cm -> new CellView(
                    "COMPOSITE_" + cm.type() + "_" + cm.role(),
                    cm.id(), cell.nutrientLevel(), flags);
        };
    }
}
