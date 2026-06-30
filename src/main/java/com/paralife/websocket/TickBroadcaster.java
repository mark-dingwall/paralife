package com.paralife.websocket;

import com.paralife.admission.OutboundSender;
import com.paralife.codec.ActiveEffect;
import com.paralife.codec.CellEntry;
import com.paralife.codec.Coord;
import com.paralife.codec.Event;
import com.paralife.codec.Frame;
import com.paralife.codec.KindData;
import com.paralife.codec.PerceptionCodec;
import com.paralife.codec.PoolSnapshot;
import com.paralife.codec.RosterMember;
import com.paralife.codec.StateChange;
import com.paralife.engine.AlarmQueue;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.CompositeRegistry;
import com.paralife.engine.Direction;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.SimulationConfig;
import com.paralife.engine.TickEvent;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.Rock;
import com.paralife.world.Entity.Role;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Plan 15-08: codec-driven tick projection (SCHEMA §6.3 / §7 / §8).
 *
 * <p>Per-bot {@link Frame.TickFrame} construction → {@link PerceptionCodec}
 * compact text encoding → WebSocket TextMessage. Jackson is gone; the
 * {@code Messages.*} record family is NOT imported (plan 15-11 deletes it).
 *
 * <p><b>Authority tiers (SCHEMA §7) — updated Phase 20.1 D-01.</b>
 * <ul>
 *   <li><b>Full</b> — solo Particle, BondedPair: sensorRadius=2 (3 with {@code SENSOR_PLUS_1}).
 *       Composite LOCOMOTOR: sensorRadius=1; union cells extend beyond radius-1.</li>
 *   <li><b>Authority-lite</b> — FEEDER / ATTACKER: sensorRadius=1, own 8-cell adjacency only.</li>
 *   <li><b>Passive</b> — SENSOR / DEFENDER / REPRODUCER: {@link Frame.TickFrame} minimal form
 *       (sensorRadius = 0 — alive + energy + own events only per §6.3.2).</li>
 * </ul>
 *
 * <p><b>Zero-trust (D-28 / T-15-03).</b> {@link CellEntry} carries no entity
 * id; bonded-secondary type is hidden (primary kind codes {@code D/N/T} only).
 * Composite members emit the role digit {@code 0}-{@code 5}; rocks {@code R};
 * nutrients {@code F}.
 *
 * <p><b>D-40 vision-scoped OVERCROWDED (preserved VERBATIM from plan 15-07).</b>
 * The {@code cellStatus = (cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit}
 * mask-and-OR is lifted intact into {@link #envStateFor}; the expression is
 * load-bearing per Phase 14 D-40 and pinned by {@code VisionScopedOvercrowdingTest}.
 *
 * <p><b>AlarmQueue drain (plan 15-06 producer, plan 15-08 consumer).</b>
 * LOCOMOTOR's v block drains {@link AlarmQueue#drainAlarms(String)} and emits
 * one {@code vN<relCoord>} event per pending alarm. Overflow past
 * {@link PerceptionCodec#MAX_V_ENTRIES} is truncated with a warn log.
 *
 * <p><b>Roster send-on-change (SCHEMA §8.5).</b> The g block ships ONLY when
 * the roster hash for this session differs from the last-sent value. A
 * per-session {@link ConcurrentHashMap} tracks the state.
 */
@Component
public class TickBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TickBroadcaster.class);

    /** Default full-authority solo/bonded sensor radius (5×5). */
    public static final int PERCEPTION_RADIUS = 2;

    /**
     * Phase 14 D-40: vision-scoped OVERCROWDED bit. The cached env cellStatus
     * byte has this bit stripped and the per-bot computed value OR'd back —
     * see {@link #envStateFor} for the load-bearing expression.
     */
    static final byte BIT_OVERCROWDED = 0x01;

    // SCHEMA §8.1.3 envState bits (match EnvironmentEngine constants).
    private static final byte BIT_TOXIN = 0x02;
    private static final byte BIT_MUTAGEN = 0x04;

    private final BotRegistry botRegistry;
    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final CompositeRegistry compositeRegistry;
    private final EnvironmentEngine environmentEngine;
    private final BuffRegistry buffRegistry;
    private final SimulationConfig simulationConfig;
    private final AlarmQueue alarmQueue;
    private final WebSocketMetrics metrics;
    /**
     * Phase 17 Plan 08: non-blocking outbound sender. Frames are enqueued here
     * rather than sent synchronously — the per-session VT drains the queue.
     * Nullable in legacy unit-test constructions that don't exercise the
     * broadcast path; guarded before use.
     */
    private OutboundSender outboundSender;
    /**
     * Phase 15.2 / Phase 17: injected lazily to avoid a bean cycle (Handler
     * already depends on TickBroadcaster indirectly via bean graph). Used for
     * {@link WorldWebSocketHandler#markDead} and (Phase 17) for
     * {@link WorldWebSocketHandler#isStalled} STALLED-skip. Nullable in
     * mock-only unit tests; guarded before use.
     */
    private WorldWebSocketHandler worldWebSocketHandler;

    /**
     * SCHEMA §8.5 g-block send-on-change: per-session last roster hash. Updated
     * after a T frame is sent; if the roster hash for the next tick differs,
     * the g block is included. {@code -1} sentinel = never-sent (first T
     * after registration always carries g).
     */
    private final Map<String, Integer> lastRosterHashBySession = new ConcurrentHashMap<>();

    @Autowired
    public TickBroadcaster(BotRegistry botRegistry, SessionRegistry sessionRegistry,
                           WorldGrid worldGrid, CompositeRegistry compositeRegistry,
                           EnvironmentEngine environmentEngine, BuffRegistry buffRegistry,
                           SimulationConfig simulationConfig, AlarmQueue alarmQueue,
                           WebSocketMetrics metrics) {
        this.botRegistry = botRegistry;
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.compositeRegistry = compositeRegistry;
        this.environmentEngine = environmentEngine;
        this.buffRegistry = buffRegistry;
        this.simulationConfig = simulationConfig;
        this.alarmQueue = alarmQueue;
        this.metrics = metrics;
    }

    /**
     * Phase 17 Plan 08: setter-injected to avoid a constructor cycle.
     * {@code required = false} keeps legacy mock-only unit tests working —
     * they construct via the primary ctor and skip this setter. When null,
     * offer is attempted without the STALLED check (safe: OutboundSender
     * returns false for detached sessions harmlessly).
     */
    @Autowired(required = false)
    public void setOutboundSender(OutboundSender sender) {
        this.outboundSender = sender;
    }

    /**
     * Phase 15.2 / Phase 17: setter-injected to avoid the constructor cycle
     * with {@link WorldWebSocketHandler} (the handler is a high-level bean,
     * the broadcaster is low-level; setter on the low-level side is the
     * standard Spring break-cycle pattern). {@code required = false} keeps
     * legacy mock-only unit tests working.
     */
    @Autowired(required = false)
    public void setWorldWebSocketHandler(@Lazy WorldWebSocketHandler handler) {
        this.worldWebSocketHandler = handler;
    }

    /**
     * Phase 19 SCALE-07 D-10 (Rule 1 — inter-run state leak fix): clears per-session
     * roster hash cache so two consecutive test runs with the same sessionIds produce
     * identical g-block suppression decisions (stale cache from run 1 would cause
     * run 2 to suppress g blocks that run 1 sent, breaking digest equivalence).
     *
     * <p>Test-only — production sessions are never reused across entity lifetimes.
     */
    public void clearStateForTest() {
        lastRosterHashBySession.clear();
    }

    @EventListener
    @Order(50) // After SimulationEngine(10) + ActionResolver(20) — tick-pipeline perception step.
    public void onTick(TickEvent event) {
        // Phase 15.2: drain death notices BEFORE iterating live bots — sessions
        // for bots that died this tick are no longer in getAllBots(), but are
        // still open. Each gets a terminal vD frame (SCHEMA §8.4 Died) so the
        // client's respawn FSM kicks off.
        drainAndBroadcastDeaths(event.tickNumber());
        // Phase 19.5 E1: same pattern for bond-formation prey sessions —
        // entity is gone but session is still open, terminal v|B frame
        // triggers the same client-side respawn FSM.
        drainAndBroadcastAbsorptions(event.tickNumber());

        var bots = botRegistry.getAllBots();
        if (bots.isEmpty()) return;

        int enqueued = 0;
        int skipped = 0;

        for (BotRegistry.BotState bot : bots) {
            WebSocketSession session = sessionRegistry.getSession(bot.sessionId());
            if (session == null || !session.isOpen()) continue;
            // Phase 17 (D-11): skip sessions in STALLED grace — OutboundSender VT detached.
            if (worldWebSocketHandler != null && worldWebSocketHandler.isStalled(session)) {
                skipped++;
                continue;
            }

            try {
                Frame.TickFrame frame = buildTickFrame(bot, event.tickNumber());
                // Phase 17 Plan 08: non-blocking enqueue; encode + recordFrameSize happen
                // inside OutboundSender.drainLoop — that is the sole measurement point
                // (codex MEDIUM: do NOT call recordFrameSize here).
                if (outboundSender != null) {
                    outboundSender.offer(bot.sessionId(), frame);
                }
                // outboundSender == null only in legacy unit tests that don't exercise
                // the broadcast path (they call buildTickFrame directly). In those cases
                // the frame is intentionally dropped — the test asserts on frame shape,
                // not on delivery.
                enqueued++;
            } catch (RuntimeException e) {
                skipped++;
                log.warn("Tick frame build failed for session {}: {}", bot.sessionId(), e.getMessage(), e);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Tick {} broadcast: enqueued={} skipped={} bots={}",
                    event.tickNumber(), enqueued, skipped, bots.size());
        }
    }

    // ── Death frame drain (Phase 15.2) ─────────────────────────────────

    /**
     * Phase 15.2: send a terminal {@code vD} frame to each session whose bot
     * died this tick. The death notice queue is populated inside
     * {@link BotRegistry#unregisterByEntity} — by the time this runs the
     * bot is no longer in {@code getAllBots()}, so without this path the
     * client would never see the own-death event and the respawn FSM
     * would never fire (Phase 15 UAT Test 7 gap).
     */
    private void drainAndBroadcastDeaths(long tickId) {
        var deaths = botRegistry.drainDeaths();
        if (deaths.isEmpty()) return;
        for (BotRegistry.DeathNotice dn : deaths) {
            WebSocketSession session = sessionRegistry.getSession(dn.sessionId());
            if (session == null || !session.isOpen()) continue;
            // Phase 17 (D-11): skip sessions in STALLED grace — sender VT detached.
            if (worldWebSocketHandler != null && worldWebSocketHandler.isStalled(session)) continue;
            // Clear the session's ATTR_ENTITY_ID so the next r| from this
            // session is accepted as a respawn (not rejected E|409). In
            // production DI this is always available; null only in legacy
            // unit-test constructions that don't exercise the respawn path.
            if (worldWebSocketHandler != null) {
                worldWebSocketHandler.markDead(session);
            }
            try {
                Frame.TickFrame frame = buildDeathFrame(tickId, dn.position());
                // Phase 17 Plan 08: non-blocking enqueue; recordFrameSize is in
                // OutboundSender.drainLoop — sole measurement point (codex MEDIUM).
                if (outboundSender != null) {
                    outboundSender.offer(dn.sessionId(), frame);
                }
                // outboundSender == null only in legacy unit tests that call
                // buildDeathFrame directly. Frame is intentionally dropped in that path.
            } catch (RuntimeException e) {
                log.warn("Death frame build failed for session {}: {}", dn.sessionId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Build a minimal-form terminal tick frame carrying a single {@code v|D}
     * event (SCHEMA §8.4). Uses the dead entity's last known position, zero
     * energy, and radius 0 — the client ignores everything but the event
     * once it sees {@code D} (see {@code BotClient.onTick}).
     */
    Frame.TickFrame buildDeathFrame(long tickId, Position lastPos) {
        List<Event> events = List.of(new Event('D', Optional.empty(), OptionalInt.empty()));
        return new Frame.TickFrame(tickId, lastPos.x(), lastPos.y(),
                /*energy=*/ 0, /*maxEnergy=*/ 0,
                /*sensorRadius=*/ 0,
                List.of(), Optional.empty(), List.of(), events,
                Optional.empty(), List.of());
    }

    /**
     * Phase 19.5 E1: drain absorbed-into-bond notices and broadcast a terminal
     * {@code v|B} frame to each prey session. Mirror of
     * {@link #drainAndBroadcastDeaths} — same skip rules (closed/STALLED
     * sessions ignored), same {@link WorldWebSocketHandler#markDead}
     * post-frame attr cleanup so a follow-up {@code r|} from the prey session
     * is accepted as a respawn.
     */
    private void drainAndBroadcastAbsorptions(long tickId) {
        var absorbed = botRegistry.drainAbsorptions();
        if (absorbed.isEmpty()) return;
        for (BotRegistry.AbsorbedNotice an : absorbed) {
            WebSocketSession session = sessionRegistry.getSession(an.sessionId());
            if (session == null || !session.isOpen()) continue;
            if (worldWebSocketHandler != null && worldWebSocketHandler.isStalled(session)) continue;
            if (worldWebSocketHandler != null) {
                worldWebSocketHandler.markDead(session);
            }
            try {
                Frame.TickFrame frame = buildAbsorbedFrame(tickId, an.position());
                if (outboundSender != null) {
                    outboundSender.offer(an.sessionId(), frame);
                }
            } catch (RuntimeException e) {
                log.warn("Absorbed frame build failed for session {}: {}", an.sessionId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Phase 19.5 E1: minimal-form terminal tick frame carrying a single
     * {@code v|B} event (SCHEMA §8.4 Bonded/absorBed). Same shape as
     * {@link #buildDeathFrame} — last known position, zero energy, radius 0.
     */
    Frame.TickFrame buildAbsorbedFrame(long tickId, Position lastPos) {
        List<Event> events = List.of(new Event('B', Optional.empty(), OptionalInt.empty()));
        return new Frame.TickFrame(tickId, lastPos.x(), lastPos.y(),
                /*energy=*/ 0, /*maxEnergy=*/ 0,
                /*sensorRadius=*/ 0,
                List.of(), Optional.empty(), List.of(), events,
                Optional.empty(), List.of());
    }

    // ── Frame construction ─────────────────────────────────────────────

    /**
     * Build the per-bot tick frame. Public to allow
     * {@code ZeroTrustFilteringTest} (in {@code com.paralife.engine}) to
     * exercise the encoder end-to-end — the frame must never carry entity
     * ids, so asserting that on encoded output IS the contract. Test-only
     * callers never send the result; production callers funnel through
     * {@link #onTick}.
     */
    public Frame.TickFrame buildTickFrame(BotRegistry.BotState bot, long tickId) {
        Position pos = bot.position();
        Cell selfCell = worldGrid.getCell(pos.x(), pos.y());
        Entity occupant = selfCell.occupant();
        AuthorityTier tier = tierOf(occupant, bot.entityId());

        int curX = pos.x();
        int curY = pos.y();
        int energy;
        int maxEnergy;
        if (occupant instanceof Particle p) {
            energy = p.energy();
            maxEnergy = p.maxEnergy();
        } else if (occupant instanceof BondedPair bp) {
            energy = bp.energy();
            maxEnergy = bp.maxEnergy();
        } else if (occupant instanceof CompositeMember cm) {
            energy = cm.energy();
            maxEnergy = cm.maxEnergy();
        } else {
            // Entity died or was displaced — emit alive-check with 0 energy.
            energy = 0;
            maxEnergy = 0;
        }

        // Minimal (passive) form: SENSOR / DEFENDER / REPRODUCER receive alive + energy + own events only.
        if (tier == AuthorityTier.PASSIVE) {
            List<Event> events = buildEventsForBot(bot, occupant, tickId, tier);
            return new Frame.TickFrame(tickId, curX, curY, energy, maxEnergy,
                    /*sensorRadius=*/ 0,
                    List.of(), Optional.empty(), List.of(), events,
                    Optional.empty(), List.of());
        }

        int radius = sensorRadiusFor(tier, bot.entityId());

        // s block — vision cells with kind-code mapping + env state bitmasks.
        // Phase 20.1 D-01: LOCOMOTOR composite members use buildLocomotorCells (radius-1 adjacency
        // + SENSOR union). FEEDER/ATTACKER use the generic buildCellEntries(radius=1, isCompositeMember).
        // Solo/bonded use buildCellEntries(radius=2/3, isCompositeMember=false).
        boolean isCompositeMember = occupant instanceof CompositeMember;
        List<CellEntry> cells;
        if (occupant instanceof CompositeMember cm && cm.role() == Role.LOCOMOTOR) {
            // D-01: LOCOMOTOR sensorRadius signal = 1 (union cells extend beyond radius-1 but
            // sensorRadius is a role signal, not a radius bound on emitted cells).
            radius = 1;
            cells = buildLocomotorCells(pos, cm.compositeId());
        } else {
            cells = buildCellEntries(pos, radius, isCompositeMember);
        }

        // c block — state-change transitions are currently produced by the
        // engine via paths that don't yet surface here; leave empty. Plan 15-08
        // scope stops at "set Optional.of when this tick triggered a transition"
        // — the event source doesn't feed TickBroadcaster in MVP. Plans 15-09+
        // wire brain-side transitions; meanwhile the block is correctly absent.
        Optional<StateChange> change = Optional.empty();

        // f block — active effects for this entity: buffs, infection, FLEEING.
        List<ActiveEffect> effects = buildEffectsForBot(bot, occupant);

        // v block — per-bot events; LOCOMOTOR also drains AlarmQueue here.
        List<Event> events = buildEventsForBot(bot, occupant, tickId, tier);

        // p block — shared-pool snapshot for full-authority LOCOMOTOR only.
        Optional<PoolSnapshot> pool = buildPool(tier, occupant);

        // g block — roster send-on-change for LOCOMOTOR only.
        List<RosterMember> roster = buildRosterIfChanged(bot, tier, occupant, pos);

        return new Frame.TickFrame(tickId, curX, curY, energy, maxEnergy,
                radius, cells, change, effects, events, pool, roster);
    }

    // ── Authority tier & sensor radius ─────────────────────────────────

    /** Authority tiers per SCHEMA §7. */
    enum AuthorityTier { FULL, AUTHORITY_LITE, PASSIVE }

    /**
     * Determine authority tier from the occupant.
     * <ul>
     *   <li>Solo Particle / BondedPair / composite LOCOMOTOR = FULL.</li>
     *   <li>FEEDER / ATTACKER = AUTHORITY_LITE (8-cell adjacency, radius-1).</li>
     *   <li>SENSOR / DEFENDER / REPRODUCER = PASSIVE (Phase 20.1 D-01: REPRODUCER is passive).</li>
     *   <li>Null/dead occupant defaults to FULL so the bot still receives an alive-check frame.</li>
     * </ul>
     */
    private AuthorityTier tierOf(Entity occupant, String botEntityId) {
        if (occupant instanceof CompositeMember cm) {
            return switch (cm.role()) {
                case LOCOMOTOR -> AuthorityTier.FULL;
                case FEEDER, ATTACKER -> AuthorityTier.AUTHORITY_LITE;
                // Phase 20.1 D-01: REPRODUCER moves to PASSIVE (minimal form, no s block).
                case SENSOR, DEFENDER, REPRODUCER -> AuthorityTier.PASSIVE;
            };
        }
        // Solo, bonded, or dead/displaced — treat as full.
        return AuthorityTier.FULL;
    }

    private int sensorRadiusFor(AuthorityTier tier, String botEntityId) {
        return switch (tier) {
            case AUTHORITY_LITE -> 1;
            case FULL -> {
                boolean hasSensorPlus = botEntityId != null
                        && buffRegistry != null
                        && buffRegistry.hasBuff(botEntityId, BuffRegistry.BuffType.SENSOR_PLUS_1);
                yield hasSensorPlus ? 3 : PERCEPTION_RADIUS;
            }
            case PASSIVE -> 0;
        };
    }

    // ── s block — vision cells ────────────────────────────────────────

    /**
     * Build {@link CellEntry} list for the NxN vision window centred on {@code botPos}.
     * Self cell at (dx,dy)=(0,0) is skipped per SCHEMA §8.1.
     *
     * <p>RLE pass: consecutive rocks along a numpad direction collapse into a
     * single {@link KindData.RockRun} starter entry when no env-state varies
     * along the run. When env differs, the run is split (starter entry + later
     * env-only supplement entries per SCHEMA §8.1.4).
     *
     * @param isCompositeMember when true, D-04 applies: OVERCROWDED bit 0 is always 0.
     */
    private List<CellEntry> buildCellEntries(Position botPos, int radius, boolean isCompositeMember) {
        int gridW = worldGrid.getWidth();
        int gridH = worldGrid.getHeight();

        // Gather raw per-cell data first; RLE pass runs after.
        int diameter = radius * 2 + 1;
        CellData[][] grid = new CellData[diameter][diameter];
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue; // self skipped
                int cx = Math.floorMod(botPos.x() + dx, gridW);
                int cy = Math.floorMod(botPos.y() + dy, gridH);
                Cell cell = worldGrid.getCell(cx, cy);
                Position cellPos = new Position(cx, cy);
                Entity occ = cell.occupant();
                Character kind = kindCodeFor(occ);
                int entityState = entityStateOf(occ);
                int envState = envStateFor(cellPos, botPos, radius, isCompositeMember) & 0xFF;
                grid[dx + radius][dy + radius] = new CellData(dx, dy, kind, entityState, envState, occ);
            }
        }

        List<CellEntry> out = new ArrayList<>();
        boolean[][] consumed = new boolean[diameter][diameter];

        // Emission in a stable order (row-major by dy then dx) to keep encoded
        // output deterministic for round-trip tests.
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue;
                int gx = dx + radius;
                int gy = dy + radius;
                CellData d = grid[gx][gy];
                if (d == null || consumed[gx][gy]) continue;
                // SCHEMA §8.1: empty cells (no kind, no env) are NOT emitted.
                // presence=0 is forbidden on the wire.
                if (d.kind == null && d.envState == 0) continue;

                if (d.kind != null && d.kind == 'R') {
                    int bestDir = 0;
                    int bestLen = 0;
                    // Prefer horizontal/vertical/diagonal runs starting here.
                    // SCHEMA §8.1.4: RLE dir is a numpad digit (1..9 excluding 5).
                    for (int[] step : RLE_STEPS) {
                        int len = measureRockRun(grid, consumed, gx, gy, step[0], step[1], d.envState, radius);
                        if (len > bestLen) {
                            bestLen = len;
                            bestDir = step[2];
                        }
                    }
                    if (bestLen >= 1 && bestDir != 0) {
                        // Run starter + bestLen additional same-env rocks.
                        consumed[gx][gy] = true;
                        int[] step = stepForDir(bestDir);
                        int sx = gx;
                        int sy = gy;
                        for (int i = 0; i < bestLen; i++) {
                            sx += step[0];
                            sy += step[1];
                            consumed[sx][sy] = true;
                        }
                        out.add(buildRockEntry(d, (char) ('0' + bestDir), bestLen));
                        continue;
                    }
                }

                // Solo entry (non-rock, or isolated rock).
                consumed[gx][gy] = true;
                out.add(buildCellEntry(d));
            }
        }

        return out;
    }

    /**
     * Phase 20.1 D-01/D-03: build the LOCOMOTOR composite member's cell list.
     *
     * <p>Result = own radius-1 adjacency ∪ each SENSOR member's radius-2 (5×5) window,
     * deduplicated by canonical LOCOMOTOR-relative (dx,dy) key, sorted by (wrappedDy, wrappedDx),
     * and clamped to {@link PerceptionCodec#MAX_S_ENTRIES}.
     *
     * <p><b>Direct-relativeTo re-expression (C2b):</b> each SENSOR CellEntry coord is
     * re-expressed to a LOCOMOTOR-relative (dx,dy) by computing the cell's ABSOLUTE position
     * (SENSOR abs pos + SENSOR-relative offset) then calling {@link #relativeTo(Position, Position)
     * relativeTo(locoPos, cellAbsPos)} directly. Offset-composition ({@code sensorCellRel +
     * relativeTo(loco, sensor)}) is WRONG across the torus seam — use the direct path only.
     *
     * <p><b>Self-cell filter:</b> a SENSOR adjacent to the LOCOMOTOR includes the LOCOMOTOR's
     * own cell in its 5×5; re-expressed that is (0,0) which is filtered out.
     *
     * <p><b>D-04:</b> both buildCellEntries call sites pass {@code isCompositeMember=true}.
     *
     * <p><b>Cross-composite guard (T-20.1-10):</b> a SENSOR's grid cell must contain a
     * {@link CompositeMember} whose {@code role()==SENSOR} AND
     * {@code compositeId().equals(compositeId)} — a foreign composite's SENSOR is excluded.
     *
     * <p><b>Optional guard (T-20.1-11):</b> if the composite is absent from the registry
     * (momentarily out-of-sync), falls back to the LOCOMOTOR's own adjacency-only without
     * throwing — no blind {@code .get()}.
     *
     * <p>SENSOR_PLUS_1 widening of SENSOR contribution radius is intentionally out of scope
     * per D-03 (future extension point).
     */
    private List<CellEntry> buildLocomotorCells(Position locoPos, String compositeId) {
        int gridW = worldGrid.getWidth();
        int gridH = worldGrid.getHeight();

        // Dedup the vision union at CELL granularity (not RLE-entry granularity): map keyed by
        // canonical LOCOMOTOR-relative (dx,dy) as a Coord.Relative record (value-equal). RLE
        // encoding happens once, AFTER the union is complete (see rleEncodeUnion) — encoding
        // earlier would let a multi-cell RockRun be deduped by its starter alone, silently
        // dropping or duplicating its SENSOR-only tail cells.
        LinkedHashMap<Coord.Relative, CellData> union = new LinkedHashMap<>();

        // LOCOMOTOR's own radius-1 adjacency (8-cell Moore ring).
        // D-04: isCompositeMember=true so OVERCROWDED bit is always 0.
        gatherLocoRelativeCells(locoPos, 1, locoPos, gridW, gridH, union);

        // Guard: if composite is absent, return adjacency-only without crashing (T-20.1-11).
        Optional<CompositeRegistry.CompositeState> coOpt = compositeRegistry.getComposite(compositeId);
        if (coOpt.isEmpty()) {
            return sortAndClamp(rleEncodeUnion(union));
        }
        CompositeRegistry.CompositeState co = coOpt.get();

        // Enumerate SENSOR members and stitch their 5x5 windows.
        for (String memberId : co.getMemberIds()) {
            Position sensorPos = co.getPositionForMember(memberId);
            if (sensorPos == null) continue;

            // Occupant-identity + role + cross-composite guard (T-20.1-10).
            Cell sensorCell = worldGrid.getCell(sensorPos.x(), sensorPos.y());
            if (!(sensorCell.occupant() instanceof CompositeMember mem)) continue;
            if (mem.role() != Role.SENSOR) continue;
            if (!mem.compositeId().equals(compositeId)) continue;

            // Stitch the SENSOR's 5x5 window. D-04: isCompositeMember=true.
            gatherLocoRelativeCells(sensorPos, PERCEPTION_RADIUS, locoPos, gridW, gridH, union);
        }

        return sortAndClamp(rleEncodeUnion(union));
    }

    /**
     * Gather non-empty cells in {@code centerPos}'s radius window, re-expressed to
     * LOCOMOTOR-relative (dx,dy), into {@code union} (cell-granularity, first-writer-wins).
     *
     * <p>Re-expression uses the DIRECT path — compute the cell's absolute position then
     * {@link #relativeTo(Position, Position) relativeTo(locoPos, cellAbs)} — not offset
     * composition, which is wrong across the torus seam. The LOCOMOTOR's own cell
     * (re-expressed (0,0)) is filtered. Coords are clamped to ±63 (wire limit), matching
     * {@link #buildRosterIfChanged}.
     */
    private void gatherLocoRelativeCells(Position centerPos, int radius, Position locoPos,
                                          int gridW, int gridH,
                                          LinkedHashMap<Coord.Relative, CellData> union) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue; // center's own cell
                int cx = Math.floorMod(centerPos.x() + dx, gridW);
                int cy = Math.floorMod(centerPos.y() + dy, gridH);
                Position cellPos = new Position(cx, cy);
                Entity occ = worldGrid.getCell(cx, cy).occupant();
                Character kind = kindCodeFor(occ);
                int envState = envStateFor(cellPos, centerPos, radius, /*isCompositeMember=*/ true) & 0xFF;
                // SCHEMA §8.1: empty cells (no kind, no env) are NOT emitted.
                if (kind == null && envState == 0) continue;

                Position locoRel = relativeTo(locoPos, cellPos);
                int rawDx = locoRel.x();
                int rawDy = locoRel.y();
                if (rawDx == 0 && rawDy == 0) continue; // LOCOMOTOR's own cell

                int wrappedDx = Math.max(-63, Math.min(63, rawDx));
                int wrappedDy = Math.max(-63, Math.min(63, rawDy));
                Coord.Relative key = new Coord.Relative(wrappedDx, wrappedDy);
                union.putIfAbsent(key, new CellData(
                        wrappedDx, wrappedDy, kind, entityStateOf(occ), envState, occ));
            }
        }
    }

    /**
     * RLE-encode the completed LOCOMOTOR-relative cell union into wire entries. Rock cells are
     * collapsed into {@link KindData.RockRun} starters (same numpad-step table as
     * {@link #buildCellEntries}); all other cells emit solo. Emission is in stable (dy,dx) order
     * so output is deterministic. Runs may now span the adjacency∪SENSOR boundary.
     */
    private List<CellEntry> rleEncodeUnion(LinkedHashMap<Coord.Relative, CellData> union) {
        List<Coord.Relative> order = new ArrayList<>(union.keySet());
        order.sort(Comparator.comparingInt(Coord.Relative::dy).thenComparingInt(Coord.Relative::dx));

        List<CellEntry> out = new ArrayList<>();
        Set<Coord.Relative> consumed = new HashSet<>();
        for (Coord.Relative pos : order) {
            if (consumed.contains(pos)) continue;
            CellData d = union.get(pos);

            if (d.kind() != null && d.kind() == 'R') {
                int bestDir = 0;
                int bestLen = 0;
                for (int[] step : RLE_STEPS) {
                    int len = measureRockRunUnion(union, consumed, pos, step[0], step[1], d.envState());
                    if (len > bestLen) {
                        bestLen = len;
                        bestDir = step[2];
                    }
                }
                if (bestLen >= 1 && bestDir != 0) {
                    consumed.add(pos);
                    int[] step = stepForDir(bestDir);
                    int cx = pos.dx();
                    int cy = pos.dy();
                    for (int i = 0; i < bestLen; i++) {
                        cx += step[0];
                        cy += step[1];
                        consumed.add(new Coord.Relative(cx, cy));
                    }
                    out.add(buildRockEntry(d, (char) ('0' + bestDir), bestLen));
                    continue;
                }
            }

            consumed.add(pos);
            out.add(buildCellEntry(d));
        }
        return out;
    }

    /**
     * Walk the union from {@code start} in (stepX,stepY), counting additional same-env rocks not
     * yet consumed. Runs cap at 63 (wire limit per {@link KindData.RockRun}). Map analogue of
     * {@link #measureRockRun} — neighbour absence (off-window or empty) terminates the run.
     */
    private static int measureRockRunUnion(LinkedHashMap<Coord.Relative, CellData> union,
                                           Set<Coord.Relative> consumed, Coord.Relative start,
                                           int stepX, int stepY, int starterEnvState) {
        int count = 0;
        int sx = start.dx() + stepX;
        int sy = start.dy() + stepY;
        while (count < 63) {
            Coord.Relative key = new Coord.Relative(sx, sy);
            CellData n = union.get(key);
            if (n == null) break;                          // off-window or empty
            if (consumed.contains(key)) break;
            if (n.kind() == null || n.kind() != 'R') break; // non-rock breaks
            if (n.envState() != starterEnvState) break;     // env differs — split
            count++;
            sx += stepX;
            sy += stepY;
        }
        return count;
    }

    /**
     * Sort cells by (wrappedDy, wrappedDx) derived from each cell's coord, then clamp to
     * {@link PerceptionCodec#MAX_S_ENTRIES}. This is the load-bearing determinism guarantee
     * (intentionally supersedes prior row-major adjacency order — Plan 04 re-baselines it).
     *
     * <p>The sort must handle both {@link Coord.Numpad} (adjacency) and {@link Coord.Relative}
     * (SENSOR union) entries by deriving a canonical (dy,dx) from each — same derivation used
     * for the dedup key above.
     */
    // Package-private (not private) so the clamp/truncation path — unreachable on the
    // perception test's 16x16 grid — can be exercised directly by a unit test.
    List<CellEntry> sortAndClamp(List<CellEntry> cells) {
        cells.sort(Comparator
                .comparingInt((CellEntry e) -> coordToDy(e.coord()))
                .thenComparingInt(e -> coordToDx(e.coord())));
        if (cells.size() > PerceptionCodec.MAX_S_ENTRIES) {
            log.warn("buildLocomotorCells: union exceeds MAX_S_ENTRIES={}; truncating {} cells",
                    PerceptionCodec.MAX_S_ENTRIES, cells.size() - PerceptionCodec.MAX_S_ENTRIES);
            // Defensive copy — subList is a view backed by the caller's list (immutable-frame convention).
            return List.copyOf(cells.subList(0, PerceptionCodec.MAX_S_ENTRIES));
        }
        return cells;
    }

    /**
     * Convert a Coord to its canonical dy (for sort key). Numpad uses Direction; Relative uses dy().
     * Absolute is not present in LOCOMOTOR union (returns 0 as fallback).
     */
    private static int coordToDy(Coord coord) {
        return switch (coord) {
            case Coord.Numpad n -> {
                Direction d = Direction.fromNumpad(n.digit());
                yield d == null ? 0 : d.dy();
            }
            case Coord.Relative r -> r.dy();
            case Coord.Absolute a -> 0;
        };
    }

    /** Canonical dx for sort key. See {@link #coordToDy}. */
    private static int coordToDx(Coord coord) {
        return switch (coord) {
            case Coord.Numpad n -> {
                Direction d = Direction.fromNumpad(n.digit());
                yield d == null ? 0 : d.dx();
            }
            case Coord.Relative r -> r.dx();
            case Coord.Absolute a -> 0;
        };
    }

    /** Numpad direction step table: [dx, dy, numpadDigit]. 5 excluded (=self). */
    private static final int[][] RLE_STEPS = new int[][] {
            {-1, -1, 1},
            { 0, -1, 2},
            { 1, -1, 3},
            {-1,  0, 4},
            { 1,  0, 6},
            {-1,  1, 7},
            { 0,  1, 8},
            { 1,  1, 9}
    };

    private static int[] stepForDir(int dir) {
        for (int[] s : RLE_STEPS) if (s[2] == dir) return s;
        throw new IllegalArgumentException("No RLE step for dir: " + dir);
    }

    /**
     * Walk grid[gx+step..][gy+step..] counting additional same-env rocks not
     * yet consumed. Runs cap at 63 (wire limit per KindData.RockRun).
     */
    private int measureRockRun(CellData[][] grid, boolean[][] consumed,
                                int gx, int gy, int stepX, int stepY,
                                int starterEnvState, int radius) {
        int diameter = radius * 2 + 1;
        int count = 0;
        int sx = gx + stepX;
        int sy = gy + stepY;
        while (sx >= 0 && sx < diameter && sy >= 0 && sy < diameter && count < 63) {
            CellData n = grid[sx][sy];
            if (n == null) break;                       // self cell or absent
            if (consumed[sx][sy]) break;
            if (n.kind == null || n.kind != 'R') break; // non-rock breaks
            if (n.envState != starterEnvState) break;   // env differs — split
            count++;
            sx += stepX;
            sy += stepY;
        }
        return count;
    }

    /** Build a starter RockRun entry (or solo rock if additionalCount == 0). */
    private CellEntry buildRockEntry(CellData d, char numpadDir, int additionalCount) {
        Coord coord = coordFor(d.dx, d.dy);
        int presence = 1 | (d.envState != 0 ? 2 : 0);
        KindData kd = (additionalCount == 0)
                ? new KindData.RockSolo()
                : new KindData.RockRun(numpadDir, additionalCount);
        OptionalInt envState = d.envState != 0 ? OptionalInt.of(d.envState) : OptionalInt.empty();
        // entityState omitted for rocks per SCHEMA §8.1.
        return new CellEntry(coord, presence, Optional.of(kd), OptionalInt.empty(), envState);
    }

    /** Build a non-run, non-empty cell entry (solo, bonded, composite, rock, nutrient). */
    private CellEntry buildCellEntry(CellData d) {
        Coord coord = coordFor(d.dx, d.dy);
        boolean hasEntity = d.kind != null;
        boolean hasEnv = d.envState != 0;
        if (!hasEntity && !hasEnv) {
            // Fully default cell — shouldn't have been added in the first place;
            // return a presence=2 env-only entry with envState 0 is illegal per
            // SCHEMA so we return an env-only entry ONLY if env is non-zero.
            // Guard: if both are zero this method caller already filtered — we
            // still must return something, emit env-only with 0 (caller must
            // prevent reaching here with all-zero; but safety: throw).
            throw new IllegalStateException("Empty cell entry requested at (" + d.dx + "," + d.dy + ")");
        }
        int presence = (hasEntity ? 1 : 0) | (hasEnv ? 2 : 0);
        Optional<KindData> kd = hasEntity
                ? Optional.of(d.kind == 'R'
                        ? new KindData.RockSolo()
                        : new KindData.Simple(d.kind))
                : Optional.empty();
        // entityState only for non-rock kinds per SCHEMA §8.1.
        boolean isRock = hasEntity && d.kind == 'R';
        OptionalInt entityState = (hasEntity && !isRock && d.entityState != 0)
                ? OptionalInt.of(d.entityState)
                : OptionalInt.empty();
        OptionalInt envState = hasEnv ? OptionalInt.of(d.envState) : OptionalInt.empty();
        return new CellEntry(coord, presence, kd, entityState, envState);
    }

    /**
     * SCHEMA §2: numpad form for single-step neighbours (|dx|<=1 && |dy|<=1),
     * 4-char relative otherwise.
     */
    private static Coord coordFor(int dx, int dy) {
        if (dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1) {
            // numpad: y+1 = top row (1-3), y=0 middle (4/6), y-1 bottom (7-9)
            // Mapping uses standard keypad:
            //   1 = SW (dx=-1, dy=+1)   2 = S (dx=0, dy=+1)    3 = SE (dx=+1, dy=+1)
            //   4 = W  (dx=-1, dy=0)                           6 = E  (dx=+1, dy=0)
            //   7 = NW (dx=-1, dy=-1)   8 = N (dx=0, dy=-1)    9 = NE (dx=+1, dy=-1)
            int digit = numpadDigit(dx, dy);
            return new Coord.Numpad((char) ('0' + digit));
        }
        return new Coord.Relative(dx, dy);
    }

    private static int numpadDigit(int dx, int dy) {
        // Caller guarantees -1 <= dx,dy <= 1 && !(dx==0 && dy==0).
        if (dy == 1) {
            return dx == -1 ? 1 : dx == 0 ? 2 : 3;
        } else if (dy == 0) {
            return dx == -1 ? 4 : 6;
        } else {
            return dx == -1 ? 7 : dx == 0 ? 8 : 9;
        }
    }

    /** SCHEMA §8.1.1 kind-code mapping. Returns {@code null} for empty cells. */
    private static Character kindCodeFor(Entity occ) {
        if (occ == null) return null;
        return switch (occ) {
            case Particle p -> switch (p.type()) {
                case CATALYST -> 'C';
                case MEMBRANE -> 'M';
                case SPORE    -> 'S';
            };
            case BondedPair bp -> switch (bp.primaryType()) {
                case CATALYST -> 'D';
                case MEMBRANE -> 'N';
                case SPORE    -> 'T';
            };
            case CompositeMember cm -> (char) ('0' + cm.role().ordinal());
            case Rock r -> 'R';
            case Nutrient n -> 'F';
        };
    }

    /**
     * Server-side entity-status lookup (SCHEMA §8.1.2). Entity id NEVER leaves
     * the server — the lookup happens here and the projected bitmask goes on
     * the wire in its place.
     */
    private int entityStateOf(Entity occ) {
        if (occ == null) return 0;
        String id = switch (occ) {
            case Particle p -> p.id();
            case BondedPair bp -> bp.id();
            case CompositeMember cm -> cm.id();
            case Rock r -> null;
            case Nutrient n -> null;
        };
        if (id == null) return 0;
        byte raw = environmentEngine != null ? environmentEngine.getEntityStatus(id) : 0;
        return raw & 0xFF;
    }

    /**
     * <b>Phase 14 D-40 vision-scoped OVERCROWDED — PRESERVED VERBATIM.</b>
     *
     * <p>Cached env cellStatus byte has its global-server OVERCROWDED bit
     * stripped; per-bot vision-scoped bit is recomputed from the Moore
     * neighbours THIS bot can see and OR'd back in. Bits 1+ (TOXIN_PRESENT,
     * MUTAGEN_ZONE) pass through unchanged.
     *
     * <p>The literal expression {@code cached & ~BIT_OVERCROWDED} is
     * grep-anchored by the plan's verify gate and pinned by
     * {@code VisionScopedOvercrowdingTest}. Do not refactor into a helper
     * method that would hide it.
     *
     * <p><b>Phase 20.1 D-04:</b> when {@code isCompositeMember=true}, OVERCROWDED bit 0
     * is always 0 — skip the per-bot recompute and return {@code cached & ~BIT_OVERCROWDED}
     * directly. Non-composite callers retain the full D-40 expression unchanged.
     */
    byte envStateFor(Position cellPos, Position botPos, int radius, boolean isCompositeMember) {
        byte cached = environmentEngine != null ? environmentEngine.getCellStatus(cellPos) : (byte) 0;
        if (isCompositeMember) {
            // D-04: composite-member frames always omit OVERCROWDED — return cached with bit 0 zeroed.
            return (byte) (cached & ~BIT_OVERCROWDED);
        }
        byte perBotOvercrowdedBit = computeVisionScopedOvercrowded(
                worldGrid, cellPos, botPos, radius, simulationConfig.overcrowdingThreshold())
                ? BIT_OVERCROWDED : 0x00;
        byte cellStatus = (byte) ((cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit);
        return cellStatus;
    }

    /** Package-private overload retained for VisionScopedOvercrowdingTest compatibility (non-composite). */
    byte envStateFor(Position cellPos, Position botPos, int radius) {
        return envStateFor(cellPos, botPos, radius, /*isCompositeMember=*/ false);
    }

    /**
     * D-40 vision-scoped overcrowding predicate.
     *
     * <p>Counts Moore neighbours occupied by Particle / BondedPair (matching
     * {@code SimulationEngine.processOvercrowding}), restricted to neighbours
     * the bot at {@code botPos} can observe within {@code radius}. Neighbours
     * outside the bot's vision are UNKNOWN — intentional incomplete-information
     * surface per Phase 14 lock.
     *
     * <p>Package-private + {@code static} for direct invocation by
     * {@code VisionScopedOvercrowdingTest} (migration pending plan 15-11).
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
                if (occ instanceof Particle || occ instanceof BondedPair) {
                    neighborCount++;
                }
            }
        }
        return neighborCount >= threshold;
    }

    private static boolean isPositionVisible(int x, int y, Position botPos, int radius,
                                              int gridW, int gridH) {
        int dx = Math.min(Math.floorMod(x - botPos.x(), gridW), Math.floorMod(botPos.x() - x, gridW));
        int dy = Math.min(Math.floorMod(y - botPos.y(), gridH), Math.floorMod(botPos.y() - y, gridH));
        return Math.max(dx, dy) <= radius;
    }

    // ── f block — effects ─────────────────────────────────────────────

    private List<ActiveEffect> buildEffectsForBot(BotRegistry.BotState bot, Entity occupant) {
        String id = bot.entityId();
        if (id == null) return List.of();
        List<ActiveEffect> out = new ArrayList<>(4);

        // Active buffs → SCHEMA §8.3 codes S/A/M/U.
        if (buffRegistry != null) {
            for (BuffRegistry.ActiveBuff b : buffRegistry.getBuffs(id)) {
                char code = effectCodeFor(b.type());
                out.add(new ActiveEffect(code, b.expiryTick(), Optional.empty()));
            }
        }

        if (environmentEngine != null) {
            // Infection → I:<expiry>. We don't know expiry directly; infection
            // duration is tick-driven via tickBuffsAndInfections. The v-block
            // M<magnitude> events carry per-tick damage; the f-block I carries
            // expiry. EnvironmentEngine does not currently expose expiry tick
            // for an infection; we emit expiry = 0 sentinel (schema allows
            // any non-negative long). Plan 15-09+ can wire precise expiry.
            if (environmentEngine.isInfected(id)) {
                out.add(new ActiveEffect('I', /*expiry=*/ 0L, Optional.empty()));
            }
            // FLEEING → F:<expiry>:<XXYY> abs strike coord.
            EnvironmentEngine.Fleeing fl = environmentEngine.getFleeing(id);
            if (fl != null) {
                out.add(new ActiveEffect('F', fl.expiryTick(),
                        Optional.of(new int[] { fl.strikeX(), fl.strikeY() })));
            }
        }

        return out;
    }

    private static char effectCodeFor(BuffRegistry.BuffType type) {
        return switch (type) {
            case SENSOR_PLUS_1 -> 'S';
            case ATTACK_PLUS_1 -> 'A';
            case MOVEMENT_PLUS_1 -> 'M';
            case UPKEEP_MINUS_1 -> 'U';
        };
    }

    // ── v block — events ───────────────────────────────────────────────

    /**
     * Per-bot event list for live bots. Own-death {@code vD} is NOT emitted
     * here — by the time a bot dies, its registry entry is gone and this
     * method is not called for it; {@link #drainAndBroadcastDeaths} handles
     * the terminal frame instead (Phase 15.2). Other event sources (damage,
     * eat, attack, lightning) flow through the engine's event queue which
     * is not yet wired into TickBroadcaster — produced but not projected.
     */
    private List<Event> buildEventsForBot(BotRegistry.BotState bot, Entity occupant,
                                           long tickId, AuthorityTier tier) {
        List<Event> out = new ArrayList<>();

        // LOCOMOTOR-only: drain composite member alarms → vN<relCoord>.
        if (occupant instanceof CompositeMember cm && cm.role() == Role.LOCOMOTOR && alarmQueue != null) {
            List<AlarmQueue.AlarmEntry> alarms = alarmQueue.drainAlarms(cm.compositeId());
            int budget = PerceptionCodec.MAX_V_ENTRIES - out.size();
            if (alarms.size() > budget) {
                log.warn("Alarm drain truncated: composite={} got={} budget={}",
                        cm.compositeId(), alarms.size(), budget);
                alarms = alarms.subList(0, Math.max(budget, 0));
            }
            for (AlarmQueue.AlarmEntry e : alarms) {
                Position rel = relativeTo(bot.position(), e.alarmingCellAbs());
                Coord coord = coordFor(rel.x(), rel.y());
                out.add(new Event('N', Optional.of(coord), OptionalInt.empty()));
            }
        }

        return out;
    }

    /**
     * Minimal relative-coord helper: toroidal-aware shortest dx/dy from bot to
     * target. Only used for alarms right now. The alarm cell is the raising
     * member's own position ({@code ActionResolver.handleAlarmAction}); since a
     * composite is always exactly two adjacent members (D-01 formation, rigid-body
     * movement preserves the ≤1 spread), this offset is bounded to ±1 and
     * {@code coordFor} always emits the numpad form — {@code Coord.Relative} (±63
     * guard) is never constructed here. If composites ever grow past two members
     * or gain SENSOR-stitching, this path must clamp pre-construction like
     * {@link #gatherLocoRelativeCells} / {@link #buildRosterIfChanged} do.
     */
    private Position relativeTo(Position from, Position to) {
        int gridW = worldGrid.getWidth();
        int gridH = worldGrid.getHeight();
        int rawDx = Math.floorMod(to.x() - from.x(), gridW);
        int rawDy = Math.floorMod(to.y() - from.y(), gridH);
        int dx = rawDx <= gridW / 2 ? rawDx : rawDx - gridW;
        int dy = rawDy <= gridH / 2 ? rawDy : rawDy - gridH;
        return new Position(dx, dy);
    }

    // ── p block — pool ────────────────────────────────────────────────

    private Optional<PoolSnapshot> buildPool(AuthorityTier tier, Entity occupant) {
        if (tier != AuthorityTier.FULL) return Optional.empty();
        if (!(occupant instanceof CompositeMember cm)) return Optional.empty();
        if (cm.role() != Role.LOCOMOTOR) return Optional.empty();
        if (compositeRegistry == null) return Optional.empty();
        var state = compositeRegistry.getComposite(cm.compositeId()).orElse(null);
        if (state == null) return Optional.empty();
        return Optional.of(new PoolSnapshot(state.getSharedPoolEnergy(), state.getMaxPoolEnergy()));
    }

    // ── g block — roster (send-on-change) ─────────────────────────────

    private List<RosterMember> buildRosterIfChanged(BotRegistry.BotState bot, AuthorityTier tier,
                                                     Entity occupant, Position botPos) {
        if (tier != AuthorityTier.FULL) return List.of();
        if (!(occupant instanceof CompositeMember cm)) return List.of();
        if (cm.role() != Role.LOCOMOTOR) return List.of();
        if (compositeRegistry == null) return List.of();

        var state = compositeRegistry.getComposite(cm.compositeId()).orElse(null);
        if (state == null) return List.of();

        // Collect (relCoord, roleDigit) for each member — excluding the LOCO
        // itself to match g-block semantics (rest of the roster).
        List<RosterMember> roster = new ArrayList<>();
        int hash = 1;
        int gridW = worldGrid.getWidth();
        int gridH = worldGrid.getHeight();
        for (String memberId : state.getMemberIds()) {
            if (memberId.equals(cm.id())) continue;
            Position mp = state.getPositionForMember(memberId);
            if (mp == null) continue;
            Cell memberCell = worldGrid.getCell(mp.x(), mp.y());
            // Cross-composite guard — mirrors the s-block SENSOR-union guard. A stale
            // registry position now holding a foreign composite's member must not be
            // emitted in this composite's roster (zero-trust; relies on more than the
            // single-threaded tick ordering invariant).
            if (!(memberCell.occupant() instanceof CompositeMember mem)
                    || !mem.compositeId().equals(cm.compositeId())) continue;
            Position rel = relativeTo(botPos, mp);
            // Clamp to [-63, 63] before coord creation (Coord.Relative enforces).
            int clampedDx = Math.max(-63, Math.min(63, rel.x()));
            int clampedDy = Math.max(-63, Math.min(63, rel.y()));
            Coord coord = coordFor(clampedDx, clampedDy);
            char roleDigit = (char) ('0' + mem.role().ordinal());
            roster.add(new RosterMember(coord, roleDigit));
            // Hash independent of grid toroid specifics — just dx/dy/role.
            hash = 31 * hash + clampedDx;
            hash = 31 * hash + clampedDy;
            hash = 31 * hash + roleDigit;
        }

        Integer prior = lastRosterHashBySession.get(bot.sessionId());
        if (prior != null && prior == hash) {
            return List.of();                  // unchanged — suppress g block
        }
        lastRosterHashBySession.put(bot.sessionId(), hash);
        return roster;
    }

    // ── Intermediate cell data ────────────────────────────────────────

    /**
     * Intermediate record used during RLE assembly. Holds per-cell data in
     * bot-relative (dx,dy) form plus the resolved kind char / states.
     */
    private record CellData(int dx, int dy, Character kind, int entityState, int envState, Entity occupant) {}
}
