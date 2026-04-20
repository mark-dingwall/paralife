package com.paralife.websocket;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.AlarmQueue;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.CompositeRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.SimulationConfig;
import com.paralife.engine.TickEvent;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.world.Entity.CompositeMember;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Plan 15-11 (Task 2): composite-member authority tier behaviour.
 *
 * <p>The pre-Phase-15 {@code Messages.CompositePerception} message type has
 * been collapsed into the single {@link Frame.TickFrame} type. Authority
 * tiers (SCHEMA §7) are distinguished by the frame shape:
 * <ul>
 *   <li><b>FULL</b> (LOCOMOTOR) — {@code sensorRadius=2 (3 with SENSOR_PLUS_1)};
 *       pool and roster blocks present.</li>
 *   <li><b>AUTHORITY_LITE</b> (FEEDER / ATTACKER / REPRODUCER) — {@code sensorRadius=1}.</li>
 *   <li><b>PASSIVE</b> (SENSOR / DEFENDER) — minimal form ({@code sensorRadius=0}).</li>
 * </ul>
 *
 * <p>This class preserves the composite-specific coverage intent of the old
 * {@code CompositePerceptionTest}: minimal form for passive members, shared
 * pool snapshot on LOCOMOTOR, and the "blind composite" (no SENSOR) still
 * receives frames — the previous "blindCompositeGetsNoPerception" assertion
 * no longer applies because the new protocol always sends a T frame to every
 * registered bot (alive-check semantics apply).
 */
class CompositePerceptionTest {

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
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

    // ── Passive members → minimal form ─────────────────────────────────

    @Test
    void sensorMemberReceivesMinimalForm() {
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, sensor);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.isMinimal())
                .as("SENSOR is PASSIVE → minimal form (sensorRadius=0)")
                .isTrue();
    }

    @Test
    void defenderMemberReceivesMinimalForm() {
        var defender = new CompositeMember("m1", "c1", ParticleType.MEMBRANE, Role.DEFENDER, 50, 100);
        worldGrid.setEntity(5, 5, defender);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.isMinimal()).isTrue();
    }

    // ── LOCOMOTOR → FULL tier with pool + roster ───────────────────────

    @Test
    void locomotorReceivesPoolAndEnergy() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 45, 100);
        worldGrid.setEntity(5, 5, loco);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 80, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 42L);

        assertThat(frame.tickId()).isEqualTo(42L);
        assertThat(frame.energy()).isEqualTo(45);
        assertThat(frame.maxEnergy()).isEqualTo(100);
        assertThat(frame.sensorRadius()).isEqualTo(TickBroadcaster.PERCEPTION_RADIUS);
        assertThat(frame.pool()).isPresent();
        assertThat(frame.pool().get().pool()).isEqualTo(80);
        assertThat(frame.pool().get().maxPool()).isEqualTo(200);
    }

    @Test
    void locomotorReceivesRosterOfOtherMembers() {
        var loco = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var feeder = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.FEEDER, 50, 100);
        var sensor = new CompositeMember("m3", "c1", ParticleType.SPORE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, loco);
        worldGrid.setEntity(6, 5, feeder);
        worldGrid.setEntity(7, 5, sensor);
        compositeRegistry.register("c1", List.of("m1", "m2", "m3"),
                Map.of("m1", new Position(5, 5), "m2", new Position(6, 5),
                        "m3", new Position(7, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.roster())
                .as("LOCOMOTOR roster contains other members (FEEDER + SENSOR)")
                .hasSize(2);
        // Roster role digits: FEEDER=1, SENSOR=5 per Role enum ordinals.
        var roles = frame.roster().stream().map(r -> r.role()).sorted().toList();
        assertThat(roles).containsExactly(
                (char) ('0' + Role.FEEDER.ordinal()),
                (char) ('0' + Role.SENSOR.ordinal()));
    }

    // ── Authority-lite members → sensorRadius=1 ────────────────────────

    @Test
    void feederAuthorityLiteRadius1() {
        var feeder = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        worldGrid.setEntity(5, 5, feeder);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);

        assertThat(frame.sensorRadius()).isEqualTo(1);
        assertThat(frame.pool())
                .as("AUTHORITY_LITE does not carry pool block")
                .isEmpty();
        assertThat(frame.roster())
                .as("AUTHORITY_LITE does not carry roster block")
                .isEmpty();
    }

    // ── onTick delivery ────────────────────────────────────────────────

    @Test
    void onTickSendsCodecEncodedFrame() throws Exception {
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, sensor);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        Frame decoded = PerceptionCodec.decode(captor.getValue().getPayload());
        assertThat(decoded).isInstanceOf(Frame.TickFrame.class);
        Frame.TickFrame tf = (Frame.TickFrame) decoded;
        assertThat(tf.isMinimal())
                .as("SENSOR → minimal form")
                .isTrue();
    }

    @Test
    void allMembersReceiveTickFrames() throws Exception {
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        var loco = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.LOCOMOTOR, 50, 100);
        worldGrid.setEntity(5, 5, sensor);
        worldGrid.setEntity(6, 5, loco);

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(6, 5)), 100, 200);

        botRegistry.register("s1", "m1", new Position(5, 5));
        botRegistry.register("s2", "m2", new Position(6, 5));

        WebSocketSession session1 = mockSession("s1");
        WebSocketSession session2 = mockSession("s2");

        broadcaster.onTick(new TickEvent(1));

        var cap1 = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        var cap2 = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(cap1.capture());
        verify(session2).sendMessage(cap2.capture());

        Frame f1 = PerceptionCodec.decode(cap1.getValue().getPayload());
        Frame f2 = PerceptionCodec.decode(cap2.getValue().getPayload());
        assertThat(f1).isInstanceOf(Frame.TickFrame.class);
        assertThat(f2).isInstanceOf(Frame.TickFrame.class);

        Frame.TickFrame t1 = (Frame.TickFrame) f1;
        Frame.TickFrame t2 = (Frame.TickFrame) f2;
        assertThat(t1.isMinimal())
                .as("m1 SENSOR → minimal")
                .isTrue();
        assertThat(t2.sensorRadius())
                .as("m2 LOCOMOTOR → full tier radius")
                .isEqualTo(TickBroadcaster.PERCEPTION_RADIUS);
    }

    @Test
    void noSensorCompositeStillSendsFrames() {
        // Pre-plan-15 semantics: a composite without SENSOR members received no
        // perception. Post-plan-15: every registered bot gets a T frame (alive
        // check). Blind-ness is expressed by the empty s block, not the absence
        // of a frame. This pins the new contract.
        var feeder = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        worldGrid.setEntity(5, 5, feeder);
        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);
        botRegistry.register("s1", "m1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        Frame.TickFrame frame = broadcaster.buildTickFrame(bot, 1L);
        // FEEDER = authority-lite → full-shape frame (not minimal), s block
        // still empty because no other entities in vision.
        assertThat(frame.isMinimal()).isFalse();
        assertThat(frame.sensorRadius()).isEqualTo(1);
        assertThat(frame.cells()).isEmpty();
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        sessionRegistry.register(s);
        return s;
    }
}
