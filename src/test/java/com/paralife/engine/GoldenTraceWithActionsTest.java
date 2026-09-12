package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.admission.OutboundSender;
import com.paralife.codec.Coord;
import com.paralife.codec.Event;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.metrics.EmergenceMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

/**
 * Phase 19.1 D-11 — golden-trace with actions gate.
 *
 * <p>Unlike {@link GoldenTraceEquivalenceTest} whose driveScenario queues NO actions,
 * this test drives deterministic M/E/R/V/A verbs through {@link ActionResolver#queueAction}
 * on every tick, pinning the resulting per-session digest baseline.
 *
 * <p>This closes the gap: Plan 02's determinism hardening had no end-to-end gate
 * exercising the verb code paths. A green baseline here confirms the parser does not
 * silently drop actions.
 *
 * <p>G1-revised (pass-6 triage 2026-05-04): V and A are included in the cycle but
 * excluded from the per-verb floor assertions — solo V/A entities silently rest at
 * Phase 2 (ActionResolver.java :503-507 and :498). They DO traverse pendingActions drain.
 *
 * <p>F3 anti-reflag (pass-5 triage 2026-05-04): Task 05f (processInteractions hoist)
 * must land BEFORE this baseline is pinned so the digest encodes post-hoist behaviour.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.simulation.spawn.seed=42",
        "paralife.simulation.seed=42",
        "paralife.simulation.action-seed=42",
        "paralife.simulation.fertility.seed=42",
        "paralife.simulation.events.seed=42",
        "paralife.composite.seed=42",
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16",
        // Force bond formation on every eligible adjacency.
        "paralife.bonding.bonding-probability=1.0",
        "paralife.bonding.bond-energy-threshold=50",
        // Disable nutrient spawning so E side-effect gate (post < pre) is reliable.
        // Nutrients are pre-seeded in setupScenario; consumption brings the count down.
        "paralife.simulation.nutrient-spawn-probability=0.0"
})
class GoldenTraceWithActionsTest {

    private static final String RESOURCE_PATH = "/golden-trace-with-actions-phase19.json";
    private static final Path SOURCE_PATH =
            Path.of("src/test/resources/golden-trace-with-actions-phase19.json");
    private static final int BOT_COUNT = 30;
    private static final int TICK_COUNT = 200;
    private static final int OUTBOUND_QUEUE_SIZE = 64;

    // ── core beans ────────────────────────────────────────────────────────────
    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired EligibleCellIndex eligibleCellIndex;
    @Autowired WorldWebSocketHandler handler;
    @Autowired OutboundSender outboundSender;
    @Autowired SessionRegistry sessionRegistry;
    @Autowired ApplicationEventPublisher applicationEventPublisher;
    @Autowired EmergenceMetrics emergenceMetrics;
    @Autowired ActionResolver actionResolver;
    @Autowired AlarmQueue alarmQueue;
    @Autowired CompositeRegistry compositeRegistry;

    // ── reset-required seeded beans ────────────────────────────────────────────
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired SimulationEngine simulationEngine;
    @Autowired CompositeEnergyDistributor compositeEnergyDistributor;
    @Autowired FertilityInitializer fertilityInitializer;
    @Autowired BuffRegistry buffRegistry;
    @Autowired DeathFinalizer deathFinalizer;
    @Autowired com.paralife.websocket.TickBroadcaster tickBroadcaster;

    private GoldenTraceCapture capture;
    private final List<String> registeredSessionIds = new ArrayList<>();

    /** Running count of M/E/R-verb queueAction calls — denominator for optional aggregate gate. */
    private int totalQueuedMER = 0;

    @BeforeEach
    void setUp() {
        capture = new GoldenTraceCapture();
        outboundSender.setFrameEmitListener(capture::onEmit);
        registeredSessionIds.clear();
        totalQueuedMER = 0;
    }

    @AfterEach
    void tearDown() {
        outboundSender.setFrameEmitListener(null);
        for (String sid : new ArrayList<>(registeredSessionIds)) {
            outboundSender.detachSession(sid);
            sessionRegistry.unregister(sid);
        }
        registeredSessionIds.clear();
    }

    // ── Test 1: baseline pin ──────────────────────────────────────────────────

    @Test
    @DisplayName("Phase 19.1 D-11 — golden trace with M/E/R/V/A verbs pins deterministic baseline")
    void goldenTraceWithActionsMatchesBaseline() throws Exception {
        resetAll();

        // Step 1: register entities (seeds + bots) — positions are now live.
        setupScenario();

        // Step 2: capture pre-tick snapshots for side-effect asserts.
        // Pre-drive snapshots AFTER entity registration — so prePositions is non-empty.
        // (pass-8 cuts: side-effect gates replace the removed LongAdder cluster)
        Map<String, Position> prePositions = captureEntityPositions();
        int preNutrientCount = countNutrients();

        // Step 3: drive ticks (queues actions each tick).
        runTicks();

        long emits = capture.emitCount();
        Map<String, String> map = capture.digestsAsHexMap();

        // --- vacuous-baseline guards ---
        assertThat(emits).as("D-11 emit count > 0").isGreaterThan(0);
        assertThat(map).as("D-11 digest map non-empty").isNotEmpty();
        map.forEach((s, h) -> assertThat(h)
                .as("D-11 per-session digest != SHA-256(empty), session=" + s)
                .isNotEqualTo(GoldenTraceCapture.EMPTY_SHA256_HEX));

        // --- post-drive side-effect gates (B5.2 / E5.3 / pass-8 cuts) ---
        // M: at least 1 entity position changed across the run
        Map<String, Position> postPositions = captureEntityPositions();
        boolean anyMoved = false;
        for (Map.Entry<String, Position> e : prePositions.entrySet()) {
            if (!e.getValue().equals(postPositions.get(e.getKey()))) {
                anyMoved = true;
                break;
            }
        }
        assertThat(anyMoved)
                .as("M gate — at least 1 entity position must change across " + TICK_COUNT + " ticks")
                .isTrue();

        // E: at least 1 nutrient was consumed (post < pre)
        int postNutrientCount = countNutrients();
        assertThat(postNutrientCount)
                .as("E gate — at least 1 nutrient must be consumed (pre=" + preNutrientCount
                        + " post=" + postNutrientCount + ")")
                .isLessThan(preNutrientCount);

        // R/B5.4: at least 1 M/E/R action was queued (non-zero denominator confirms
        // the trace drove actions, not a vacuous empty run).
        assertThat(totalQueuedMER)
                .as("B5.4 gate — at least 1 M/E/R action was queued")
                .isGreaterThan(0);

        // --- EXPECTED_DIGESTS resource ---
        Map<String, String> expected = loadExpectedDigests();
        if (expected == null || expected.containsKey("_seed_marker")) {
            writeBaseline(map);
            fail("BASELINE_PINNED — re-run test, file written to " + SOURCE_PATH.toAbsolutePath());
        }
        assertThat(map)
                .as("D-11 EXPECTED_DIGESTS pinned at " + RESOURCE_PATH
                        + "; Task 05a must not change the per-session digests.")
                .isEqualTo(expected);
    }

    // ── Test 2: L alarm routing ───────────────────────────────────────────────

    @Test
    @DisplayName("L alarm is projected once to the explicit composite LOCOMOTOR")
    void lAlarmIsProjectedOnceToExplicitLocomotor() throws Exception {
        resetAll();
        AlarmFixture fixture = setUpAlarmFixture();
        List<String> locoFrames = captureFramesFor(fixture.locomotorSessionId());

        // Prime ActionResolver's current-tick clock before the synchronous L route.
        actionResolver.onTick(new TickEvent(70L));
        actionResolver.queueAction(
                fixture.alarmingSessionId(), new Frame.ActionFrame('L', Optional.empty()));

        tickBroadcaster.onTick(new TickEvent(71L));
        awaitAllSessionQueuesDrained();
        awaitCapturedFrameCount(locoFrames, 1);

        assertThat(locoFrames).hasSize(1);
        String firstWire = locoFrames.get(0);
        Frame.TickFrame first = decodeTick(firstWire);
        assertThat(firstWire).contains("v4N");
        assertThat(first.events())
                .containsExactly(new Event(
                        'N', Optional.of(new Coord.Numpad('4')), OptionalInt.empty()));

        tickBroadcaster.onTick(new TickEvent(72L));
        awaitAllSessionQueuesDrained();
        awaitCapturedFrameCount(locoFrames, 2);

        assertThat(locoFrames).hasSize(2);
        assertThat(decodeTick(locoFrames.get(1)).events()).isEmpty();
    }

    @Test
    @DisplayName("LOCOMOTOR alarm projection retains FIFO prefix, drops overflow, and never replays it")
    void alarmProjectionCapsRetainedFifoAndDropsOverflowAfterDrain() throws Exception {
        resetAll();
        AlarmFixture fixture = setUpAlarmFixture();
        List<String> locoFrames = captureFramesFor(fixture.locomotorSessionId());

        alarmQueue.enqueueAlarm(fixture.compositeId(), new Position(5, 6), 80L); // west → 4
        alarmQueue.enqueueAlarm(fixture.compositeId(), new Position(6, 5), 81L); // north → 8
        for (int i = 2; i < PerceptionCodec.MAX_V_ENTRIES; i++) {
            alarmQueue.enqueueAlarm(fixture.compositeId(), new Position(5, 6), 80L + i);
        }
        alarmQueue.enqueueAlarm(fixture.compositeId(), new Position(7, 6), 112L); // overflow east → 6

        tickBroadcaster.onTick(new TickEvent(113L));
        awaitAllSessionQueuesDrained();
        awaitCapturedFrameCount(locoFrames, 1);

        assertThat(locoFrames).hasSize(1);
        Frame.TickFrame capped = decodeTick(locoFrames.get(0));
        assertThat(capped.events()).hasSize(PerceptionCodec.MAX_V_ENTRIES);
        assertThat(capped.events().get(0).coord()).contains(new Coord.Numpad('4'));
        assertThat(capped.events().get(1).coord()).contains(new Coord.Numpad('8'));
        assertThat(capped.events().subList(2, PerceptionCodec.MAX_V_ENTRIES))
                .allSatisfy(event -> {
                    assertThat(event.code()).isEqualTo('N');
                    assertThat(event.coord()).contains(new Coord.Numpad('4'));
                });
        assertThat(capped.events())
                .noneSatisfy(event -> assertThat(event.coord()).contains(new Coord.Numpad('6')));

        tickBroadcaster.onTick(new TickEvent(114L));
        awaitAllSessionQueuesDrained();
        awaitCapturedFrameCount(locoFrames, 2);

        assertThat(locoFrames).hasSize(2);
        assertThat(decodeTick(locoFrames.get(1)).events()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AlarmFixture setUpAlarmFixture() {
        String compositeId = "alarm-composite";
        alarmQueue.drainAlarms(compositeId);
        String alarmingEntityId = "alarm-feeder";
        String alarmingSessionId = "alarm-feeder-session";
        String locomotorEntityId = "alarm-locomotor";
        String locomotorSessionId = "alarm-locomotor-session";
        Position alarmingPosition = new Position(5, 6);
        Position locomotorPosition = new Position(6, 6);

        worldGrid.setEntity(alarmingPosition.x(), alarmingPosition.y(),
                new Entity.CompositeMember(alarmingEntityId, compositeId,
                        Entity.ParticleType.CATALYST, Entity.Role.FEEDER, 50, 100));
        worldGrid.setEntity(locomotorPosition.x(), locomotorPosition.y(),
                new Entity.CompositeMember(locomotorEntityId, compositeId,
                        Entity.ParticleType.MEMBRANE, Entity.Role.LOCOMOTOR, 50, 100));
        compositeRegistry.register(
                compositeId,
                List.of(alarmingEntityId, locomotorEntityId),
                Map.of(alarmingEntityId, alarmingPosition, locomotorEntityId, locomotorPosition),
                100,
                200);
        registerAlarmSession(alarmingSessionId, alarmingEntityId, alarmingPosition);
        registerAlarmSession(locomotorSessionId, locomotorEntityId, locomotorPosition);

        return new AlarmFixture(compositeId, alarmingSessionId, locomotorSessionId);
    }

    private void registerAlarmSession(String sessionId, String entityId, Position position) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(sessionId);
        sessionRegistry.register(session);
        outboundSender.attachSession(session, OUTBOUND_QUEUE_SIZE);
        botRegistry.register(sessionId, entityId, position);
        registeredSessionIds.add(sessionId);
    }

    private List<String> captureFramesFor(String sessionId) {
        List<String> frames = new CopyOnWriteArrayList<>();
        outboundSender.setFrameEmitListener((emittedSessionId, bytes) -> {
            if (sessionId.equals(emittedSessionId)) {
                frames.add(new String(bytes, StandardCharsets.UTF_8));
            }
        });
        return frames;
    }

    private static Frame.TickFrame decodeTick(String wire) {
        return (Frame.TickFrame) PerceptionCodec.decode(wire);
    }

    private static void awaitCapturedFrameCount(List<String> frames, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (frames.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(frames).hasSize(expected);
    }

    private record AlarmFixture(
            String compositeId,
            String alarmingSessionId,
            String locomotorSessionId) {}

    private Map<String, Position> captureEntityPositions() {
        Map<String, Position> positions = new HashMap<>();
        for (LiveEntityRegistry.EntityEntry e : liveEntityRegistry.snapshot()) {
            positions.put(e.entityId(), e.position());
        }
        return positions;
    }

    private int countNutrients() {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                if (worldGrid.getCell(x, y).occupant() instanceof Entity.Nutrient) {
                    count++;
                }
            }
        }
        return count;
    }

    private Map<String, String> loadExpectedDigests() throws Exception {
        try (InputStream is = getClass().getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) return null;
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> raw =
                    mapper.readValue(is, new TypeReference<Map<String, String>>() {});
            return new TreeMap<>(raw);
        }
    }

    private void writeBaseline(Map<String, String> digests) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> sorted = new TreeMap<>(digests);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sorted);
        Files.createDirectories(SOURCE_PATH.getParent());
        Files.writeString(SOURCE_PATH, json);
    }

    /**
     * Cross-run cleanup (mirrors GoldenTraceEquivalenceTest.resetAll).
     */
    private void resetAll() {
        for (String sid : new ArrayList<>(registeredSessionIds)) {
            outboundSender.detachSession(sid);
            sessionRegistry.unregister(sid);
        }
        registeredSessionIds.clear();

        worldGrid.clear();
        compositeRegistry.clear();
        buffRegistry.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest();
        deathFinalizer.resetCountForTest();

        environmentEngine.resetForTest();
        simulationEngine.resetSeed();
        actionResolver.resetSeed();
        actionResolver.clearStateForTest();
        simulationEngine.clearStateForTest();
        compositeEnergyDistributor.resetSeed();
        fertilityInitializer.resetSeed();

        tickBroadcaster.clearStateForTest();

        eligibleCellIndex.rebuildForTest();
        handler.resetSeed();
    }

    /**
     * Phase 1 of scenario execution: seed bonding pairs, nutrients, and register bots.
     * Called before capturing pre-tick position snapshots so those snapshots are non-empty.
     * Separated from runTicks() so callers can observe pre-tick state.
     */
    private void setupScenario() throws Exception {
        // Pre-seed nutrients so the E side-effect gate (post < pre) is satisfiable.
        // Without pre-seeded nutrients, preNutrientCount=0 at grid-clear time and
        // the assertion `post < pre` can never pass. Positions chosen to avoid
        // the bonding seed locations at (5,5),(5,6),(9,9),(9,10).
        int[][] nutrientPos = {
            {0,0},{1,1},{2,2},{3,3},{4,4},{0,8},{1,9},{2,10},{3,11},{4,12},
            {8,0},{8,1},{8,2},{8,3},{8,4},{12,8},{12,9},{12,10},{12,11},{12,12}
        };
        for (int i = 0; i < nutrientPos.length; i++) {
            worldGrid.setEntity(nutrientPos[i][0], nutrientPos[i][1],
                    Entity.Nutrient.spawn("nutrient-pre-" + i));
        }

        // Seed two adjacent bonding pairs (mirrors GoldenTraceEquivalenceTest pattern).
        seedAdjacentBondingPair("seed-pred-1", "seed-prey-1",
                5, 5, 5, 6,
                Entity.ParticleType.CATALYST, Entity.ParticleType.CATALYST.prey(), 200);
        seedAdjacentBondingPair("seed-pred-2", "seed-prey-2",
                9, 9, 9, 10,
                Entity.ParticleType.MEMBRANE, Entity.ParticleType.MEMBRANE.prey(), 200);

        // Register the remaining bots (26 = BOT_COUNT - 4).
        for (int i = 0; i < BOT_COUNT - 4; i++) {
            String entityId = "trace-bot-" + i;
            String sessionId = "trace-sess-" + i;

            Optional<Position> pos = handler.attemptPlacementForTest(
                    entityId, Entity.ParticleType.values()[i % 3], 100);
            assertThat(pos).as("placement must succeed for bot " + i).isPresent();

            WebSocketSession mockSession = mock(WebSocketSession.class);
            when(mockSession.isOpen()).thenReturn(true);
            when(mockSession.getId()).thenReturn(sessionId);

            sessionRegistry.register(mockSession);
            outboundSender.attachSession(mockSession, OUTBOUND_QUEUE_SIZE);
            botRegistry.register(sessionId, entityId, pos.get());
            liveEntityRegistry.register(entityId, pos.get());

            registeredSessionIds.add(sessionId);
        }
    }

    /**
     * Phase 2 of scenario execution: drive TICK_COUNT ticks, queuing M/E/R/V/A verbs.
     *
     * G1-revised: V and A excluded from per-verb floors; both traverse pendingActions drain.
     * C5.3: tick driving via applicationEventPublisher.publishEvent.
     */
    private void runTicks() throws Exception {
        for (int t = 0; t < TICK_COUNT; t++) {
            var liveSnapshot = liveEntityRegistry.snapshot();
            for (var entry : liveSnapshot) {
                // G1-revised: 5-verb cycle {M,E,R,V,A}.
                // V and A solo entities silently rest at Phase 2 (ActionResolver.java
                // :503-507 and :498); they DO traverse pendingActions drain.
                // L excluded — :367-372 dispatches L synchronously, bypassing drainActions.
                char verb = switch (t % 5) {
                    case 0 -> 'M';
                    case 1 -> 'E';
                    case 2 -> 'R';
                    case 3 -> 'V';
                    default -> 'A';
                };
                // H1: seedAdjacentBondingPair entities are NOT in botRegistry — skip them.
                Optional<String> sessionOpt = botRegistry.getSessionByEntity(entry.entityId());
                if (sessionOpt.isEmpty()) continue;
                String sessionId = sessionOpt.get();

                // G1-revised: accumulate denominator for M/E/R only.
                if (verb == 'M' || verb == 'E' || verb == 'R') {
                    totalQueuedMER++;
                }
                // B5.1: queueAction is session-keyed.
                actionResolver.queueAction(sessionId, buildDeterministicAction(verb, entry.entityId(), t));
            }
            // C5.3: mirror GoldenTraceEquivalenceTest.driveScenario verbatim.
            applicationEventPublisher.publishEvent(new TickEvent(t));
            awaitAllSessionQueuesDrained();
        }
    }

    /**
     * Phase 19.1 D-11 — deterministic per-verb ActionFrame builder.
     * B5.2: payloads use numpad chars ('2','4','6','8' = S/W/E/N), not integer indices.
     */
    private Frame.ActionFrame buildDeterministicAction(char verb, String entityId, int tick) {
        int seed = (entityId.hashCode() ^ tick);
        return switch (verb) {
            case 'M' -> new Frame.ActionFrame('M', Optional.of(numpadDir(seed)));
            case 'E' -> new Frame.ActionFrame('E', Optional.empty());
            case 'R' -> new Frame.ActionFrame('R', Optional.of(numpadDir(seed)));
            case 'V' -> new Frame.ActionFrame('V', Optional.of(numpadBallot(seed)));
            case 'A' -> new Frame.ActionFrame('A', Optional.of(numpadDir(seed)));
            default -> throw new IllegalArgumentException("verb: " + verb);
        };
    }

    /** Deterministic numpad direction from seed. Chars '2','4','6','8' = S/W/E/N. */
    private static String numpadDir(int seed) {
        char[] dirs = {'2', '4', '6', '8'};
        return String.valueOf(dirs[Math.floorMod(seed, 4)]);
    }

    /** Deterministic 3-char numpad ballot from seed. */
    private static String numpadBallot(int seed) {
        char[] dirs = {'2', '4', '6', '8'};
        int a = Math.floorMod(seed, 4);
        int b = Math.floorMod(seed / 4, 4);
        int c = Math.floorMod(seed / 16, 4);
        return new String(new char[]{dirs[a], dirs[b], dirs[c]});
    }

    /** Place predator+prey adjacent; register in LiveEntityRegistry (NOT botRegistry — H1). */
    private void seedAdjacentBondingPair(
            String predId, String preyId,
            int px, int py, int qx, int qy,
            Entity.ParticleType predType, Entity.ParticleType preyType,
            int maxEnergy) {
        Entity.Particle pred = Entity.Particle.spawn(predId, predType, maxEnergy);
        Entity.Particle prey = Entity.Particle.spawn(preyId, preyType, maxEnergy);
        assertThat(worldGrid.trySetEntity(px, py, pred))
                .as("predator placement at (" + px + "," + py + ") must succeed")
                .isTrue();
        assertThat(worldGrid.trySetEntity(qx, qy, prey))
                .as("prey placement at (" + qx + "," + qy + ") must succeed")
                .isTrue();
        liveEntityRegistry.register(predId, new Position(px, py));
        liveEntityRegistry.register(preyId, new Position(qx, qy));
        eligibleCellIndex.notifyChanged(px, py);
        eligibleCellIndex.notifyChanged(qx, qy);
    }

    /**
     * REVIEWS CONSENSUS-H3: post-queueDepth-zero synchronized(session) barrier.
     */
    private void awaitAllSessionQueuesDrained() throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean allDrained = true;
            for (String sid : registeredSessionIds) {
                if (outboundSender.queueDepth(sid) > 0) {
                    allDrained = false;
                    break;
                }
            }
            if (allDrained) {
                for (String sid : registeredSessionIds) {
                    WebSocketSession s = sessionRegistry.getSession(sid);
                    if (s != null) {
                        synchronized (s) {
                            // No-op — monitor acquire/release is the barrier.
                        }
                    }
                }
                return;
            }
            Thread.sleep(1);
        }
        throw new IllegalStateException(
                "OutboundSender queues did not drain in 2s for sessions=" + registeredSessionIds);
    }
}
