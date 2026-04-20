package com.paralife.websocket;

import com.paralife.codec.CellEntry;
import com.paralife.codec.Coord;
import com.paralife.codec.Frame;
import com.paralife.codec.KindData;
import com.paralife.engine.AlarmQueue;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.CompositeRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.SimulationConfig;
import com.paralife.engine.TickEvent;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Plan 15-11 (Task 2): projection-layer test for the codec-driven
 * {@link TickBroadcaster}.
 *
 * <p>Migrated from the Messages.Perception / CellView record fixtures to the
 * post-plan-15-08 {@link Frame.TickFrame} + {@link CellEntry} shape (SCHEMA
 * §6.3 / §8.1). Every assertion that previously matched an entity-id field
 * has been replaced with a type (kind code) + position (coord) check — the
 * zero-trust invariant (D-28 / T-15-03) removes ids from the wire.
 */
class TickBroadcasterProjectionTest {

    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private WorldGrid worldGrid;
    private CompositeRegistry compositeRegistry;
    private WebSocketMetrics metrics;
    private BuffRegistry buffRegistry;
    private SimulationConfig simConfig;
    private AlarmQueue alarmQueue;
    private EnvironmentEngine envEngineMock;
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
        broadcaster = new TickBroadcaster(botRegistry, sessionRegistry, worldGrid,
                compositeRegistry, envEngineMock, buffRegistry, simConfig, alarmQueue, metrics);
    }

    // ── Frame-shape tests (formerly "self" entity state + radius) ──────

    @Test
    void tickFrameCarriesPositionAndEnergyForSoloParticle() {
        Particle particle = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, particle);
        botRegistry.register("s1", "e1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 42L);

        assertThat(frame.tickId()).isEqualTo(42L);
        assertThat(frame.curX()).isEqualTo(5);
        assertThat(frame.curY()).isEqualTo(5);
        assertThat(frame.energy()).isEqualTo(Particle.DEFAULT_START_ENERGY);
        assertThat(frame.sensorRadius())
                .as("FULL-tier solo Particle sees 5x5 (radius=2)")
                .isEqualTo(TickBroadcaster.PERCEPTION_RADIUS);
    }

    @Test
    void tickFrameNeighbourhoodRadius() {
        Particle particle = Particle.spawn("e1", ParticleType.SPORE);
        worldGrid.setEntity(5, 5, particle);
        botRegistry.register("s1", "e1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.sensorRadius()).isEqualTo(TickBroadcaster.PERCEPTION_RADIUS);
        // No non-empty neighbours → s block empty (SCHEMA §8.1: empty cells not emitted).
        assertThat(frame.cells()).isEmpty();
    }

    @Test
    void tickFrameShowsNearbyEntitiesWithCorrectKindCodes() {
        // Self at (5,5), MEMBRANE east at (6,5), Rock at (4,4).
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        botRegistry.register("s1", "e1", new Position(5, 5));

        Particle neighbor = Particle.spawn("e2", ParticleType.MEMBRANE);
        worldGrid.setEntity(6, 5, neighbor);
        worldGrid.setEntity(4, 4, new Entity.Rock("rock1"));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        // East neighbour: numpad 6 with kind 'M'.
        CellEntry east = findNumpadEntry(frame, '6').orElseThrow();
        assertThat(kindCodeOf(east)).isEqualTo('M');

        // NW rock: numpad 7 with kind 'R'.
        CellEntry nw = findNumpadEntry(frame, '7').orElseThrow();
        assertThat(kindCodeOf(nw)).isEqualTo('R');
    }

    @Test
    void tickFrameWrapsToroidally() {
        Particle self = Particle.spawn("e1", ParticleType.SPORE);
        worldGrid.setEntity(0, 0, self);
        botRegistry.register("s1", "e1", new Position(0, 0));

        // (15,15) wraps to NW of (0,0) on a 16×16 grid — numpad 7 from centre.
        Particle wrapped = Particle.spawn("e2", ParticleType.CATALYST);
        worldGrid.setEntity(15, 15, wrapped);

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        CellEntry nw = findNumpadEntry(frame, '7').orElseThrow();
        assertThat(kindCodeOf(nw)).isEqualTo('C');
    }

    // ── Empty-cell suppression ─────────────────────────────────────────

    @Test
    void emptyCellsOmittedFromSBlock() {
        // Lone bot, no neighbours: presence=0 entries are NOT emitted per SCHEMA §8.1.
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        botRegistry.register("s1", "e1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);
        assertThat(frame.cells())
                .as("Empty cells must not be emitted — self cell is skipped, all others empty")
                .isEmpty();
    }

    // ── Kind-code mapping ──────────────────────────────────────────────

    @Test
    void rockEntryHasKindR() {
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        worldGrid.setEntity(6, 5, new Entity.Rock("r1"));
        botRegistry.register("s1", "e1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        CellEntry east = findNumpadEntry(frame, '6').orElseThrow();
        assertThat(kindCodeOf(east)).isEqualTo('R');
    }

    @Test
    void nutrientEntryHasKindF() {
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        worldGrid.setEntity(6, 5, Entity.Nutrient.spawn("n1"));
        botRegistry.register("s1", "e1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        CellEntry east = findNumpadEntry(frame, '6').orElseThrow();
        assertThat(kindCodeOf(east)).isEqualTo('F');
    }

    @Test
    void bondedPairEntryUsesPrimaryKindCode() {
        // BondedPair primary=CATALYST → kind 'D' per SCHEMA §8.1.1.
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        Entity.BondedPair bp = new Entity.BondedPair(
                "bond-1", ParticleType.CATALYST, ParticleType.SPORE, 80, 200);
        worldGrid.setEntity(6, 5, bp);
        botRegistry.register("s1", "e1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        CellEntry east = findNumpadEntry(frame, '6').orElseThrow();
        assertThat(kindCodeOf(east))
                .as("Bonded primary=CATALYST projects as 'D'")
                .isEqualTo('D');
    }

    // ── onTick end-to-end (mocked session) ─────────────────────────────

    @Test
    void onTickSendsFrameToRegisteredBots() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        Particle particle = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, particle);
        botRegistry.register("s1", "e1", new Position(5, 5));

        broadcaster.onTick(new TickEvent(1));

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void onTickSkipsClosedSessions() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(false);
        sessionRegistry.register(session);

        botRegistry.register("s1", "e1", new Position(5, 5));

        broadcaster.onTick(new TickEvent(1));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void onTickNoBots() {
        broadcaster.onTick(new TickEvent(1));
    }

    // ── Authority tiers (LOCOMOTOR / PASSIVE / AUTHORITY_LITE) ─────────

    @Test
    void compositeSensorMemberReceivesMinimalForm() {
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, sensor);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.isMinimal())
                .as("SENSOR = passive → minimal form per SCHEMA §6.3.2")
                .isTrue();
        assertThat(frame.cells()).isEmpty();
        assertThat(frame.effects()).isEmpty();
        assertThat(frame.pool()).isEmpty();
        assertThat(frame.roster()).isEmpty();
    }

    @Test
    void authorityLiteFeederHasSensorRadius1() {
        var feeder = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        worldGrid.setEntity(5, 5, feeder);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.sensorRadius())
                .as("FEEDER = authority-lite → radius 1 (3x3) per SCHEMA §7")
                .isEqualTo(1);
    }

    @Test
    void locomotorReceivesPoolSnapshotAndRoster() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var feeder = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.FEEDER, 50, 100);
        worldGrid.setEntity(5, 5, loco);
        worldGrid.setEntity(6, 5, feeder);
        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(6, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.sensorRadius())
                .as("LOCOMOTOR = FULL tier → radius 2 default")
                .isEqualTo(TickBroadcaster.PERCEPTION_RADIUS);
        assertThat(frame.pool())
                .as("LOCOMOTOR carries pool snapshot")
                .isPresent();
        assertThat(frame.pool().get().pool()).isEqualTo(100);
        assertThat(frame.pool().get().maxPool()).isEqualTo(200);
        assertThat(frame.roster())
                .as("LOCOMOTOR carries roster of other members")
                .isNotEmpty();
        assertThat(frame.roster().get(0).role())
                .as("FEEDER roster entry has role digit '1'")
                .isEqualTo('1');
    }

    // ── Phase 14 Plan 05: cellStatus / entityStatus projection ─────────

    @Test
    void envStateProjectedOnNeighbourCells() {
        // Mutagen (bit 2) projected on the east neighbour's envState.
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        // Place a rock so a cell entry is emitted at (6,5). Otherwise empty
        // cells with non-zero env DO still emit (presence=2), so a rock isn't
        // strictly required — but a rock gives a stable kind to find by.
        worldGrid.setEntity(6, 5, new Entity.Rock("r1"));
        botRegistry.register("s1", "e1", new Position(5, 5));

        when(envEngineMock.getCellStatus(new Position(6, 5)))
                .thenReturn(EnvironmentEngine.CELL_STATUS_MUTAGEN_ZONE);

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        CellEntry east = findNumpadEntry(frame, '6').orElseThrow();
        assertThat(east.envState()).isPresent();
        assertThat(east.envState().getAsInt() & EnvironmentEngine.CELL_STATUS_MUTAGEN_ZONE)
                .as("MUTAGEN_ZONE bit projected from cache")
                .isEqualTo(EnvironmentEngine.CELL_STATUS_MUTAGEN_ZONE);
    }

    @Test
    void entityStateProjectedForNeighbourEntity() {
        // BUFFED bit on a neighbour entity flows into its entityState on the wire.
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        Particle neighbour = Particle.spawn("e2", ParticleType.SPORE);
        worldGrid.setEntity(6, 5, neighbour);
        botRegistry.register("s1", "e1", new Position(5, 5));

        when(envEngineMock.getEntityStatus("e2"))
                .thenReturn(EnvironmentEngine.ENTITY_STATUS_BUFFED);

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        CellEntry east = findNumpadEntry(frame, '6').orElseThrow();
        assertThat(east.entityState()).isPresent();
        assertThat(east.entityState().getAsInt() & EnvironmentEngine.ENTITY_STATUS_BUFFED)
                .isEqualTo(EnvironmentEngine.ENTITY_STATUS_BUFFED);
    }

    @Test
    void soloSensorBuffExpandsRadiusToSeven() {
        Particle bot = Particle.spawn("e1", ParticleType.SPORE);
        worldGrid.setEntity(5, 5, bot);
        botRegistry.register("s1", "e1", new Position(5, 5));

        // Baseline — no buff → radius 2 (PERCEPTION_RADIUS).
        var b = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame baseline = broadcaster.buildTickFrame(b, 1L);
        assertThat(baseline.sensorRadius()).isEqualTo(TickBroadcaster.PERCEPTION_RADIUS);

        // SENSOR_PLUS_1 → radius 3 (7×7).
        buffRegistry.grant("e1", BuffRegistry.BuffType.SENSOR_PLUS_1, 1_000L);
        Frame.TickFrame expanded = broadcaster.buildTickFrame(b, 2L);
        assertThat(expanded.sensorRadius()).isEqualTo(3);
    }

    @Test
    void bondedPairWithSensorBuffExpandsToRadius3() {
        Entity.BondedPair bp = new Entity.BondedPair("bp1", ParticleType.CATALYST,
                ParticleType.MEMBRANE, 80, 200);
        worldGrid.setEntity(5, 5, bp);
        botRegistry.register("s1", bp.id(), new Position(5, 5));

        var b = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame baseline = broadcaster.buildTickFrame(b, 1L);
        assertThat(baseline.sensorRadius()).isEqualTo(TickBroadcaster.PERCEPTION_RADIUS);

        buffRegistry.grant(bp.id(), BuffRegistry.BuffType.SENSOR_PLUS_1, 1_000L);
        Frame.TickFrame expanded = broadcaster.buildTickFrame(b, 2L);
        assertThat(expanded.sensorRadius()).isEqualTo(3);
    }

    @Test
    void visionScopedOvercrowdingPredicateCountsVisibleMooreNeighbours() {
        // D-40 predicate: for a cell to carry the per-bot OVERCROWDED bit, its
        // Moore neighbours visible to the bot must meet the threshold. Pack six
        // Particles around (10,10), then query the predicate at (10,10) — all
        // eight Moore neighbours of (10,10) are within the bot's r=2 vision, so
        // the threshold-6 count triggers.
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(10, 10, self);
        botRegistry.register("s1", "e1", new Position(10, 10));

        worldGrid.setEntity(10, 9, Particle.spawn("n1", ParticleType.CATALYST));
        worldGrid.setEntity(10, 11, Particle.spawn("n2", ParticleType.CATALYST));
        worldGrid.setEntity(9, 10, Particle.spawn("n3", ParticleType.CATALYST));
        worldGrid.setEntity(11, 10, Particle.spawn("n4", ParticleType.CATALYST));
        worldGrid.setEntity(9, 9, Particle.spawn("n5", ParticleType.CATALYST));
        worldGrid.setEntity(11, 11, Particle.spawn("n6", ParticleType.CATALYST));

        int threshold = simConfig.overcrowdingThreshold();
        boolean ovc = TickBroadcaster.computeVisionScopedOvercrowded(
                worldGrid, new Position(10, 10), new Position(10, 10),
                /*radius*/ 2, threshold);
        assertThat(ovc)
                .as("6 visible neighbours meets threshold=6 → OVERCROWDED (D-40 predicate)")
                .isTrue();

        // Guard: a cell far from the bot's vision window should NOT be overcrowded
        // even if it has dense global neighbours — the predicate only counts
        // neighbours WITHIN vision. Here we use a radius-0 bot to force that.
        boolean ovcInvisible = TickBroadcaster.computeVisionScopedOvercrowded(
                worldGrid, new Position(10, 10), new Position(10, 10),
                /*radius*/ 0, threshold);
        assertThat(ovcInvisible)
                .as("Zero-radius vision → no neighbours visible → predicate false")
                .isFalse();
    }

    @Test
    void overcrowdedBitIsPerBotNotGlobalFromCache() {
        // cycle-6 MEDIUM #9: global cache says bit-0 set, but bot vision sees
        // no dense neighbours → per-bot bit 0 MUST be 0.
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        // Rock at (6,5) so the cell is emitted.
        worldGrid.setEntity(6, 5, new Entity.Rock("r1"));
        botRegistry.register("s1", "e1", new Position(5, 5));

        Position target = new Position(6, 5);
        when(envEngineMock.getCellStatus(target)).thenReturn((byte) 0x01);

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        CellEntry east = findNumpadEntry(frame, '6').orElseThrow();
        // envState may be empty if the mask + stripped global == 0. Either absent
        // or present-with-bit-0-clear is acceptable — the invariant is bit 0 = 0
        // in the projected byte.
        int envBits = east.envState().orElse(0);
        assertThat(envBits & 0x01)
                .as("cycle-6 MEDIUM #9: bit 0 recomputed per-bot; globally-overcrowded cell presents as 0")
                .isEqualTo(0);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static Optional<CellEntry> findNumpadEntry(Frame.TickFrame frame, char digit) {
        return frame.cells().stream()
                .filter(ce -> ce.coord() instanceof Coord.Numpad n && n.digit() == digit)
                .findFirst();
    }

    private static Character kindCodeOf(CellEntry ce) {
        return ce.kind().map(kd -> switch (kd) {
            case KindData.Simple s -> s.code();
            case KindData.RockSolo ignored -> 'R';
            case KindData.RockRun run -> 'R';
        }).orElse(null);
    }
}
