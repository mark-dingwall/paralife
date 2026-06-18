package com.paralife.websocket;

import com.paralife.admission.OutboundSender;
import com.paralife.codec.CellEntry;
import com.paralife.codec.Coord;
import com.paralife.codec.Frame;
import com.paralife.codec.KindData;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.AlarmQueue;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.CompositeRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.SimulationConfig;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 20.1 Plan 01 — SensorStitchedPerceptionTest.
 *
 * <p>Pins the D-01/D-03/D-04 contracts for the SENSOR-stitched LOCOMOTOR frame:
 * <ul>
 *   <li>LOCOMOTOR frame = radius-1 adjacency ∪ SENSOR 5x5 windows, deduplicated and sorted.</li>
 *   <li>Adding a SENSOR strictly widens (never shrinks) the LOCOMOTOR's visible cell set.</li>
 *   <li>FEEDER/ATTACKER receive only their own 8-cell adjacency; SENSOR-only cells absent.</li>
 *   <li>OVERCROWDED bit 0 is always 0 in every composite-member frame (D-04).</li>
 *   <li>Union cell coords are expressed relative to the LOCOMOTOR via direct relativeTo, not offset-composition.</li>
 *   <li>Dedup key is sign-safe (negative dx/dy cells not lost or duplicated).</li>
 * </ul>
 *
 * <p>Tests are RED until Task 2 (TickBroadcaster per-role routing + buildLocomotorCells) lands.
 */
class SensorStitchedPerceptionTest {

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private CompositeRegistry compositeRegistry;
    private WebSocketMetrics metrics;
    private BuffRegistry buffRegistry;
    private SimulationConfig simConfig;
    private AlarmQueue alarmQueue;
    private EnvironmentEngine envEngineMock;
    private OutboundSender outboundSenderMock;
    private TickBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        metrics = new WebSocketMetrics(new SimpleMeterRegistry());
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry(metrics);
        compositeRegistry = new CompositeRegistry();
        buffRegistry = new BuffRegistry();
        simConfig = SimulationConfig.defaults();
        alarmQueue = new AlarmQueue();
        envEngineMock = mock(EnvironmentEngine.class);
        when(envEngineMock.getCellStatus(any())).thenReturn((byte) 0);
        when(envEngineMock.getEntityStatus(any())).thenReturn((byte) 0);
        // Phase 17 Plan 08: inject mock OutboundSender
        outboundSenderMock = mock(OutboundSender.class);
        when(outboundSenderMock.offer(anyString(), any(Frame.class))).thenReturn(true);
        broadcaster = new TickBroadcaster(botRegistry, sessionRegistry, worldGrid,
                compositeRegistry, envEngineMock, buffRegistry, simConfig, alarmQueue, metrics);
        broadcaster.setOutboundSender(outboundSenderMock);
    }

    // ── ADJACENCY-CARDINALITY ──────────────────────────────────────────────────

    /**
     * A LOCOMOTOR with NO SENSOR members has frame.cells() with at most 8 cells (its
     * own Moore ring). None of those cells has the self-relative coord (0,0).
     *
     * <p>To make the bound non-vacuous: seed all 8 neighbours with a Nutrient
     * so they are non-empty and are emitted. Exactly 8 must appear.
     */
    @Test
    void adjacencyCardinality_locomotorWithoutSensor_atMost8Cells() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        worldGrid.setEntity(5, 5, loco);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        // Seed all 8 Moore neighbours with nutrients so they emit
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                worldGrid.setEntity(5 + dx, 5 + dy, Nutrient.spawn("n"));
            }
        }

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        // With 8 non-empty neighbours exactly 8 emitted; none is self
        assertThat(frame.cells().size())
                .as("LOCOMOTOR with no SENSORs: exactly 8 Moore-ring cells emitted")
                .isEqualTo(8);

        boolean hasSelf = frame.cells().stream().anyMatch(e -> isZeroZero(e.coord()));
        assertThat(hasSelf)
                .as("No self-cell (0,0) in LOCOMOTOR frame")
                .isFalse();
    }

    // ── NEAR-BLIND BASELINE ───────────────────────────────────────────────────

    /**
     * A LOCOMOTOR in a composite with ZERO SENSOR members gets at most 8-cell
     * adjacency — no 5x5 union leaked in.
     */
    @Test
    void nearBlindBaseline_locomotorInCompositeWithNoSensor_atMost8Cells() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var feeder = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.FEEDER, 50, 100);
        worldGrid.setEntity(4, 4, loco);
        worldGrid.setEntity(4, 5, feeder);
        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(4, 4), "m2", new Position(4, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(4, 4));

        // Place a nutrient outside LOCOMOTOR radius-1 (dx=+2)
        worldGrid.setEntity(4 + 2, 4, Nutrient.spawn("n"));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.cells().size())
                .as("NEAR-BLIND BASELINE: no SENSORs → at most 8 cells")
                .isLessThanOrEqualTo(8);
    }

    // ── D-03-MONOTONIC ────────────────────────────────────────────────────────

    /**
     * Adding a SENSOR to a composite strictly widens (never shrinks) the
     * LOCOMOTOR's visible cell set.
     */
    @Test
    void d03Monotonic_addingSensorWidensLocomotorCells() {
        // Scenario A: LOCOMOTOR alone (no SENSOR)
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        worldGrid.setEntity(8, 8, loco);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(8, 8)), 100, 200);
        botRegistry.register("s1", "m1", new Position(8, 8));

        // Nutrient at (8,11): 3 steps south, outside LOCOMOTOR radius-1 but inside
        // a SENSOR at (8,10)'s 5x5 (covers y∈{8..12}).
        worldGrid.setEntity(8, 11, Nutrient.spawn("n"));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frameWithoutSensor = broadcaster.buildTickFrame(bot, 1L);
        int countWithoutSensor = frameWithoutSensor.cells().size();

        // Scenario B: same LOCOMOTOR + a SENSOR at (8,10)
        var sensor = new CompositeMember("m2", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(8, 10, sensor);
        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(8, 8), "m2", new Position(8, 10)), 100, 200);
        botRegistry.register("s2", "m2", new Position(8, 10));

        Frame.TickFrame frameWithSensor = broadcaster.buildTickFrame(bot, 2L);
        int countWithSensor = frameWithSensor.cells().size();

        assertThat(countWithSensor)
                .as("D-03-MONOTONIC: adding SENSOR widens LOCOMOTOR cells")
                .isGreaterThan(countWithoutSensor);
    }

    // ── MAX_S_ENTRIES BOUND ───────────────────────────────────────────────────

    /**
     * The LOCOMOTOR frame.cells().size() must never exceed PerceptionCodec.MAX_S_ENTRIES.
     *
     * <p>NOTE (round-4): A >256 behavioural trigger of the runtime clamp is NOT
     * constructible on this 16×16 test grid (only 256 total cells; buildCellEntries
     * skips empty cells). The clamp's truncation (subList + single log.warn) is
     * verified by INSPECTION — it IS load-bearing at the production 256×256 grid.
     * This test is the unit-level upper-bound sanity check on the multi-SENSOR scenario.
     */
    @Test
    void maxSEntriesClamp_oversizedUnion_truncatesToLimitInSortedOrder() {
        // The clamp path is unreachable on the 16x16 perception grid (a union of mutually
        // adjacent SENSORs is ≈75 cells « 256), so it is tested directly against
        // sortAndClamp with a synthetic oversized list. This pins BOTH the load-bearing
        // (dy,dx) determinism guarantee AND the truncation behaviour.
        List<CellEntry> oversized = new ArrayList<>();
        for (int dy = -8; dy <= 8; dy++) {        // 17 x 17 = 289 distinct keys > MAX_S_ENTRIES (256)
            for (int dx = -8; dx <= 8; dx++) {
                oversized.add(new CellEntry(new Coord.Relative(dx, dy), 1,
                        Optional.of(new KindData.Simple('F')), OptionalInt.empty(), OptionalInt.empty()));
            }
        }

        List<CellEntry> result = broadcaster.sortAndClamp(oversized);

        assertThat(result)
                .as("oversized union must be truncated to MAX_S_ENTRIES")
                .hasSize(PerceptionCodec.MAX_S_ENTRIES);
        // Sorted ascending by (dy, dx): each entry ≤ the next.
        for (int i = 1; i < result.size(); i++) {
            Coord.Relative prev = (Coord.Relative) result.get(i - 1).coord();
            Coord.Relative cur  = (Coord.Relative) result.get(i).coord();
            boolean ordered = prev.dy() < cur.dy() || (prev.dy() == cur.dy() && prev.dx() <= cur.dx());
            assertThat(ordered)
                    .as("entries must be sorted by (dy,dx): " + prev + " then " + cur)
                    .isTrue();
        }
        // Truncation keeps the lowest (dy,dx) entries: global-min present, global-max dropped.
        assertThat(result.get(0).coord())
                .as("lowest (dy,dx) entry must survive")
                .isEqualTo(new Coord.Relative(-8, -8));
        assertThat(result.stream().map(CellEntry::coord))
                .as("highest (dy,dx) entry must be truncated away")
                .doesNotContain(new Coord.Relative(8, 8));
    }

    // ── NUMERIC-ORIGIN ────────────────────────────────────────────────────────

    /**
     * Pins the direct-relativeTo re-expression: a SENSOR-only cell appears in
     * the LOCOMOTOR frame at the exact LOCOMOTOR-relative (dx,dy) — NOT SENSOR-relative.
     *
     * <p>LOCOMOTOR at (5,5), SENSOR at (5,8), nutrient at (5,10).
     * SENSOR-relative to nutrient: dy=+2 (relative to SENSOR at y=8).
     * LOCOMOTOR-relative to nutrient: dy=+5 (relative to LOCOMOTOR at y=5).
     * So the nutrient appears at Coord.Relative(0,+5) in the LOCOMOTOR frame.
     */
    @Test
    void numericOrigin_sensorOnlyNutrientAppearsAtLocoRelativeCoord() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var sensor = new CompositeMember("m2", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, loco);
        worldGrid.setEntity(5, 8, sensor);
        // Nutrient at (5,10): LOCO-relative (dx=0, dy=+5); SENSOR-relative (dx=0, dy=+2)
        worldGrid.setEntity(5, 10, Nutrient.spawn("n"));

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(5, 8)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        // Entry at LOCO-relative (0,5) must exist
        boolean hasLocoRelEntry = frame.cells().stream().anyMatch(e -> {
            if (e.coord() instanceof Coord.Relative r) {
                return r.dx() == 0 && r.dy() == 5;
            }
            return false;
        });
        assertThat(hasLocoRelEntry)
                .as("NUMERIC-ORIGIN: nutrient at (5,10) appears at LOCOMOTOR-relative Coord.Relative(0,5)")
                .isTrue();

        // Must NOT appear at SENSOR-relative (0,2)
        boolean hasSensorRelEntry = frame.cells().stream().anyMatch(e -> {
            if (e.coord() instanceof Coord.Relative r) {
                return r.dx() == 0 && r.dy() == 2;
            }
            return false;
        });
        assertThat(hasSensorRelEntry)
                .as("NUMERIC-ORIGIN: no entry at SENSOR-relative (0,2) in LOCOMOTOR frame")
                .isFalse();

        // The entry carries kind (it's a Nutrient)
        var nutrientEntry = frame.cells().stream()
                .filter(e -> e.coord() instanceof Coord.Relative r && r.dx() == 0 && r.dy() == 5)
                .findFirst().orElse(null);
        assertThat(nutrientEntry).as("entry at Coord.Relative(0,5) exists").isNotNull();
        assertThat(nutrientEntry.kind()).as("entry carries kind (nutrient)").isPresent();
    }

    // ── SEAM-WRAP (direct relativeTo vs offset-composition) ──────────────────

    /**
     * LOCOMOTOR at (0,0), SENSOR at (7,0), target nutrient at (9,0).
     *
     * <p>DIRECT relativeTo((0,0),(9,0)): rawDx=9; 9 > dim/2=8 → dx=9-16=-7.
     * Result: Coord.Relative(-7,0).
     *
     * <p>A WRONG composed-offset impl computes SENSOR-relative (+2) +
     * relativeTo(loco, sensor)(+7) = +9, which ≠ -7.
     *
     * <p>Also dispositive that (9,0) is at radius-7 from LOCOMOTOR — only via SENSOR.
     */
    @Test
    // SEAM-WRAP
    void seamWrap_directRelativeToNotOffsetComposition() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        // SENSOR at (7,0): 5x5 covers x∈{5..9}, y∈{14,15,0,1,2} (toroidal)
        var sensor = new CompositeMember("m2", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(0, 0, loco);
        worldGrid.setEntity(7, 0, sensor);
        // Target at (9,0): inside SENSOR window, crosses dim/2 from LOCOMOTOR
        worldGrid.setEntity(9, 0, Nutrient.spawn("n"));

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(0, 0), "m2", new Position(7, 0)), 100, 200);
        botRegistry.register("s1", "m1", new Position(0, 0));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        boolean hasDirectRelEntry = frame.cells().stream().anyMatch(e -> {
            if (e.coord() instanceof Coord.Relative r) {
                return r.dx() == -7 && r.dy() == 0;
            }
            return false;
        });
        assertThat(hasDirectRelEntry)
                .as("SEAM-WRAP: nutrient at (9,0) appears at Coord.Relative(-7,0) via direct relativeTo")
                .isTrue();

        // The composed-offset wrong result (+9,0) must NOT be present
        boolean hasComposedRelEntry = frame.cells().stream().anyMatch(e -> {
            if (e.coord() instanceof Coord.Relative r) {
                return r.dx() == 9 && r.dy() == 0;
            }
            return false;
        });
        assertThat(hasComposedRelEntry)
                .as("SEAM-WRAP: no entry at composed-offset result Coord.Relative(9,0)")
                .isFalse();
    }

    // ── SELF-CELL (0,0) FILTER ────────────────────────────────────────────────

    /**
     * When a SENSOR is adjacent to the LOCOMOTOR, the SENSOR's 5x5 includes the
     * LOCOMOTOR's own cell (re-expressed that is (0,0)). It must be filtered out.
     */
    @Test
    void selfCellFilter_sensorAdjacentToLocomotor_noZeroZeroEntry() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        // SENSOR directly north (dy=-1) → SENSOR's 5x5 includes LOCOMOTOR's cell at SENSOR-relative (0,+1)
        var sensor = new CompositeMember("m2", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, loco);
        worldGrid.setEntity(5, 4, sensor);

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(5, 4)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        boolean hasSelfCell = frame.cells().stream().anyMatch(e -> isZeroZero(e.coord()));
        assertThat(hasSelfCell)
                .as("SELF-CELL (0,0) FILTER: no entry with re-expressed coord (0,0) in LOCOMOTOR frame")
                .isFalse();
    }

    // ── NEGATIVE-COORD DEDUP ──────────────────────────────────────────────────

    /**
     * HIGHEST-LEVERAGE: breaks the signed-shift dedup-key trap.
     *
     * <p>LOCOMOTOR at (8,8), SENSOR at (5,5) — NW of LOCOMOTOR.
     * SENSOR's 5x5 covers x∈{3..7}, y∈{3..7}. Nutrient at (4,4):
     * LOCO-relative (dx=-4, dy=-4).
     *
     * <p>A bare signed-shift key `(wrappedDy << 16) | wrappedDx` corrupts negative
     * values. A Coord.Relative record key or axis-masked int key is correct.
     */
    @Test
    void negativeCoordDedup_sensorNorthWestOfLocomotor_cellAppearsExactlyOnce() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var sensor = new CompositeMember("m2", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(8, 8, loco);
        worldGrid.setEntity(5, 5, sensor); // NW of LOCOMOTOR, delta (-3,-3)

        // Nutrient at (4,4): SENSOR-relative (dx=-1, dy=-1); LOCO-relative (dx=-4, dy=-4)
        worldGrid.setEntity(4, 4, Nutrient.spawn("n"));

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(8, 8), "m2", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(8, 8));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        long count = frame.cells().stream()
                .filter(e -> e.coord() instanceof Coord.Relative r && r.dx() == -4 && r.dy() == -4)
                .count();
        assertThat(count)
                .as("NEGATIVE-COORD DEDUP: nutrient at LOCO-relative (-4,-4) appears exactly once")
                .isEqualTo(1);
    }

    // ── CROSS-COMPOSITE EXCLUSION ─────────────────────────────────────────────

    /**
     * A foreign-composite SENSOR at a position this composite's registry enumerates
     * must NOT have its vision stitched into this LOCOMOTOR's frame.
     *
     * <p>THIS composite registers SENSOR "m2" at position (5,8). We then overwrite
     * the grid cell at (5,8) with a FOREIGN-composite SENSOR (compositeId="foreignC").
     * A nutrient visible only through that foreign SENSOR's 5x5 must be ABSENT.
     *
     * <p>CRITICAL: the foreign SENSOR sits EXACTLY at the position this composite's
     * getMemberIds→getPositionForMember returns — so the guard IS reached.
     */
    @Test
    // CROSS-COMPOSITE
    void crossCompositeExclusion_foreignSensorNotStitched() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        worldGrid.setEntity(5, 5, loco);

        // Register THIS composite with SENSOR "m2" at (5,8)
        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(5, 8)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        // Overwrite (5,8) with a FOREIGN composite's SENSOR
        var foreignSensor = new CompositeMember("mX", "foreignC", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 8, foreignSensor);

        // Nutrient at (5,10): LOCO-relative (dx=0, dy=+5), only reachable via a SENSOR at (5,8)
        worldGrid.setEntity(5, 10, Nutrient.spawn("n"));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        // The nutrient at (5,10) must be ABSENT (foreign compositeId blocks stitch)
        boolean hasLeakedCell = frame.cells().stream().anyMatch(e -> {
            if (e.coord() instanceof Coord.Relative r) {
                return r.dx() == 0 && r.dy() == 5;
            }
            return false;
        });
        assertThat(hasLeakedCell)
                .as("CROSS-COMPOSITE: foreign compositeId SENSOR's cell must not appear in LOCOMOTOR frame")
                .isFalse();
    }

    // ── ADJACENCY/SENSOR OVERLAP DEDUP ────────────────────────────────────────

    /**
     * A cell visible both via LOCOMOTOR's own radius-1 (Coord.Numpad) AND via a SENSOR's
     * 5x5 (Coord.Relative after re-expression) must appear EXACTLY ONCE.
     *
     * <p>LOCOMOTOR at (5,5), SENSOR at (5,4) (north, dy=-1).
     * Cell (6,4) = LOCO-relative (dx=+1,dy=-1) = Numpad '9'. Also in SENSOR's 5x5.
     * Dedup must unify the Numpad'9' key and the Relative(+1,-1) key.
     */
    @Test
    void adjacencySensorOverlapDedup_sharedCellAppearsExactlyOnce() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var sensor = new CompositeMember("m2", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, loco);
        worldGrid.setEntity(5, 4, sensor); // SENSOR 1 north of LOCOMOTOR

        // Cell (6,4): in LOCO adjacency as Numpad '9' AND in SENSOR's 5x5 as Relative(+1,0) re-expressed
        worldGrid.setEntity(6, 4, Nutrient.spawn("n"));

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(5, 4)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        // Cell at LOCO-relative (dx=+1, dy=-1) must appear exactly once
        long count = frame.cells().stream()
                .filter(e -> cellMatchesDxDy(e.coord(), 1, -1))
                .count();
        assertThat(count)
                .as("ADJACENCY/SENSOR OVERLAP DEDUP: cell at (dx=+1,dy=-1) appears exactly once")
                .isEqualTo(1);
    }

    // ── ORDER-STABILITY ───────────────────────────────────────────────────────

    /**
     * Building the same LOCOMOTOR frame twice against an identical world state yields
     * list-equal cells() (order + contents) — not merely equal count.
     */
    @Test
    void orderStability_buildingFrameTwiceYieldsListEqualCells() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var s1e = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.SENSOR, 50, 100);
        var s2e = new CompositeMember("m3", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(8, 8, loco);
        worldGrid.setEntity(8, 6, s1e);  // SENSOR north
        worldGrid.setEntity(6, 8, s2e);  // SENSOR west

        compositeRegistry.register("c1", List.of("m1", "m2", "m3"),
                Map.of("m1", new Position(8, 8),
                       "m2", new Position(8, 6),
                       "m3", new Position(6, 8)), 100, 200);
        botRegistry.register("s1", "m1", new Position(8, 8));

        worldGrid.setEntity(8, 4, Nutrient.spawn("n"));  // inside s1e's 5x5
        worldGrid.setEntity(4, 8, Nutrient.spawn("n"));  // inside s2e's 5x5

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame1 = broadcaster.buildTickFrame(bot, 1L);
        Frame.TickFrame frame2 = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame1.cells())
                .as("ORDER-STABILITY: two builds of same world state yield list-equal cells()")
                .isEqualTo(frame2.cells());
    }

    // ── FEEDER/ATTACKER NO-LEAK ───────────────────────────────────────────────

    /**
     * FEEDER and ATTACKER frames do NOT contain cells visible only through the SENSOR's 5x5.
     */
    @Test
    void feederAttackerNoLeak_sensorOnlyCell_absentFromFeederAndAttackerFrames() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var sensor = new CompositeMember("m2", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        var feeder = new CompositeMember("m3", "c1", ParticleType.MEMBRANE, Role.FEEDER, 50, 100);
        var attacker = new CompositeMember("m4", "c1", ParticleType.CATALYST, Role.ATTACKER, 50, 100);
        worldGrid.setEntity(8, 8, loco);
        worldGrid.setEntity(8, 11, sensor);  // SENSOR 3 south
        worldGrid.setEntity(8, 9, feeder);   // FEEDER 1 south
        worldGrid.setEntity(7, 8, attacker); // ATTACKER 1 west

        compositeRegistry.register("c1", List.of("m1", "m2", "m3", "m4"),
                Map.of("m1", new Position(8, 8),
                       "m2", new Position(8, 11),
                       "m3", new Position(8, 9),
                       "m4", new Position(7, 8)), 100, 200);
        botRegistry.register("sloco", "m1", new Position(8, 8));
        botRegistry.register("sfeed", "m3", new Position(8, 9));
        botRegistry.register("satk",  "m4", new Position(7, 8));

        // Nutrient at (8,13): LOCO-relative dy=+5, only via SENSOR at (8,11)'s 5x5 (covers y∈{9..13})
        worldGrid.setEntity(8, 13, Nutrient.spawn("n"));

        var feederBot   = botRegistry.getBySession("sfeed").orElseThrow();
        var attackerBot = botRegistry.getBySession("satk").orElseThrow();

        Frame.TickFrame feederFrame   = broadcaster.buildTickFrame(feederBot, 1L);
        Frame.TickFrame attackerFrame = broadcaster.buildTickFrame(attackerBot, 1L);

        // FEEDER and ATTACKER must have only radius-1 cells (no Coord.Relative with |dx|>1 or |dy|>1)
        boolean feederLeak = feederFrame.cells().stream().anyMatch(e -> {
            if (e.coord() instanceof Coord.Relative r) {
                return Math.abs(r.dy()) > 1 || Math.abs(r.dx()) > 1;
            }
            return false;
        });
        assertThat(feederLeak)
                .as("FEEDER NO-LEAK: no SENSOR-only cells (outside radius-1) in FEEDER frame")
                .isFalse();

        boolean attackerLeak = attackerFrame.cells().stream().anyMatch(e -> {
            if (e.coord() instanceof Coord.Relative r) {
                return Math.abs(r.dy()) > 1 || Math.abs(r.dx()) > 1;
            }
            return false;
        });
        assertThat(attackerLeak)
                .as("ATTACKER NO-LEAK: no SENSOR-only cells (outside radius-1) in ATTACKER frame")
                .isFalse();
    }

    // ── FEEDER/ATTACKER OWN-CELL D-04 ────────────────────────────────────────

    /**
     * In a composite, FEEDER own-adjacency cells must have OVERCROWDED bit 0 unset (D-04),
     * even when the env returns OVERCROWDED=1 for every cell.
     */
    @Test
    void feederOwnCellD04_overcrowdedBitAlwaysZero() {
        var feeder = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        worldGrid.setEntity(5, 5, feeder);

        // Force env to return OVERCROWDED=1 for all cells
        when(envEngineMock.getCellStatus(any())).thenReturn((byte) 1);

        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        // Place non-empty neighbours so cells are emitted
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                worldGrid.setEntity(5 + dx, 5 + dy, Nutrient.spawn("n"));
            }
        }

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        // Every emitted cell must have OVERCROWDED bit 0 unset
        frame.cells().forEach(e ->
                assertThat(e.envState().orElse(0) & 0x01)
                        .as("D-04: OVERCROWDED bit must be 0 for FEEDER member cells (entry: " + e.coord() + ")")
                        .isEqualTo(0));
    }

    // ── SENSOR-CONTRIBUTED COORD TYPE ────────────────────────────────────────

    /**
     * SENSOR-contributed cells (beyond radius-1) are expressed with Coord.Relative,
     * never Coord.Absolute.
     */
    @Test
    void sensorContributedCells_haveCoordRelativeNotAbsolute() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var sensor = new CompositeMember("m2", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, loco);
        worldGrid.setEntity(5, 8, sensor);
        worldGrid.setEntity(5, 10, Nutrient.spawn("n"));  // dy=+5 from LOCOMOTOR, SENSOR-only

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(5, 8)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        // SENSOR-contributed entry at (0,5) must be Coord.Relative
        boolean hasSensorEntry = frame.cells().stream()
                .anyMatch(e -> e.coord() instanceof Coord.Relative r && r.dx() == 0 && r.dy() == 5);
        assertThat(hasSensorEntry)
                .as("SENSOR-contributed cell uses Coord.Relative type")
                .isTrue();

        // No Coord.Absolute anywhere in frame
        boolean hasAbsolute = frame.cells().stream()
                .anyMatch(e -> e.coord() instanceof Coord.Absolute);
        assertThat(hasAbsolute)
                .as("No Coord.Absolute in LOCOMOTOR frame")
                .isFalse();
    }

    // ── MISSING-COMPOSITE FALLBACK ────────────────────────────────────────────

    /**
     * A LOCOMOTOR whose composite registry entry is absent at frame-build time (the
     * "composite dissolved mid-tick" production scenario) must return its own radius-1
     * adjacency WITHOUT throwing — exercising the {@code coOpt.isEmpty()} guard in
     * buildLocomotorCells, not a blind {@code .get()}.
     *
     * <p>We register the composite, then {@code dissolve()} it (registry-only; the grid
     * occupant stays), so the LOCOMOTOR member is still on the grid with compositeId "c1"
     * while {@code getComposite("c1")} returns empty — directly hitting the guard rather
     * than verifying it by inspection.
     */
    @Test
    void missingCompositeFallback_dissolvedComposite_returnsAdjacencyOnly() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        worldGrid.setEntity(5, 5, loco);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));
        // Seed all 8 Moore neighbours so adjacency emits cells — keeps the assertion
        // non-vacuous (a trivially-empty frame would pass <= 8 regardless).
        worldGrid.setEntity(4, 4, Nutrient.spawn("n1"));
        worldGrid.setEntity(5, 4, Nutrient.spawn("n2"));
        worldGrid.setEntity(6, 4, Nutrient.spawn("n3"));
        worldGrid.setEntity(4, 5, Nutrient.spawn("n4"));
        worldGrid.setEntity(6, 5, Nutrient.spawn("n5"));
        worldGrid.setEntity(4, 6, Nutrient.spawn("n6"));
        worldGrid.setEntity(5, 6, Nutrient.spawn("n7"));
        worldGrid.setEntity(6, 6, Nutrient.spawn("n8"));

        // Force the absent-composite branch: registry entry gone, grid occupant remains.
        compositeRegistry.dissolve("c1");
        assertThat(compositeRegistry.getComposite("c1"))
                .as("precondition: composite must be absent to exercise the isEmpty() guard")
                .isEmpty();

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);
        assertThat(frame).isNotNull();
        // Adjacency-only: the 8 seeded neighbours, no SENSOR union (composite is gone).
        assertThat(frame.cells().size())
                .as("MISSING-COMPOSITE FALLBACK: dissolved-composite LOCOMOTOR gets adjacency-only (== 8)")
                .isEqualTo(8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isZeroZero(Coord coord) {
        return switch (coord) {
            case Coord.Relative r -> r.dx() == 0 && r.dy() == 0;
            case Coord.Numpad n   -> false; // numpad '5' = self but it's never emitted
            case Coord.Absolute a -> false;
        };
    }

    /** True if the coord maps to the given (dx,dy) in the LOCOMOTOR-relative convention. */
    private static boolean cellMatchesDxDy(Coord coord, int dx, int dy) {
        return switch (coord) {
            case Coord.Relative r -> r.dx() == dx && r.dy() == dy;
            case Coord.Numpad n -> {
                // Numpad convention per SCHEMA §2 / TickBroadcaster numpadDigit()
                char d = n.digit();
                int ndx = (d == '1' || d == '4' || d == '7') ? -1
                         : (d == '3' || d == '6' || d == '9') ? +1 : 0;
                int ndy = (d == '7' || d == '8' || d == '9') ? -1
                         : (d == '1' || d == '2' || d == '3') ? +1 : 0;
                yield ndx == dx && ndy == dy;
            }
            case Coord.Absolute a -> false;
        };
    }
}
