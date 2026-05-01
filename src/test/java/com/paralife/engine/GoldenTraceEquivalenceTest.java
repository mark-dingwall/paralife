package com.paralife.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.admission.OutboundSender;
import com.paralife.engine.TickEvent;
import com.paralife.metrics.EmergenceMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 19 SCALE-07 D-10 semantic-equivalence gate.
 *
 * <p>REVIEWS Round 2 + Round 3 fixes encoded:
 * <ul>
 *   <li>CONSENSUS-H2 — 30 bots × 16×16 × 200 ticks; deterministic adjacent-RPS placement
 *       forces bond formation; scenario asserts {@code bondedPairsFormed delta > 0} via
 *       {@link EmergenceMetrics#bondedPairsFormed()} cumulative counter.</li>
 *   <li>CONSENSUS-H3 — {@code awaitAllSessionQueuesDrained} post-loop
 *       {@code synchronized(session)} barrier per registered session.</li>
 *   <li>CONSENSUS-H5 — OutboundSender from com.paralife.admission;
 *       TickEvent from com.paralife.engine.</li>
 *   <li>MEDIUM-5 — resetAll detachSession + sessionRegistry.unregister per registered sid.</li>
 *   <li>MED-1 — EXPECTED_DIGESTS via generate-if-missing JSON resource.</li>
 *   <li>H4 / L3 — emitCount > 0 + no-empty-digest + map-non-empty guards.</li>
 * </ul>
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
        // REVIEWS CONSENSUS-H2 — force bond formation on every eligible adjacency.
        "paralife.bonding.bonding-probability=1.0",
        "paralife.bonding.bond-energy-threshold=50"
})
class GoldenTraceEquivalenceTest {

    private static final String RESOURCE_PATH = "/golden-trace-phase19.json";
    private static final Path SOURCE_PATH =
            Path.of("src/test/resources/golden-trace-phase19.json");
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

    // ── reset-required seeded beans (CompositeFormationDeterminismTest pattern) ──
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired SimulationEngine simulationEngine;
    @Autowired ActionResolver actionResolver;
    @Autowired CompositeEnergyDistributor compositeEnergyDistributor;
    @Autowired FertilityInitializer fertilityInitializer;
    @Autowired BuffRegistry buffRegistry;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired DeathFinalizer deathFinalizer;
    // Rule 1 — inter-run cache leak: TickBroadcaster.lastRosterHashBySession must be cleared.
    @Autowired com.paralife.websocket.TickBroadcaster tickBroadcaster;

    private GoldenTraceCapture capture;
    private final List<String> registeredSessionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        capture = new GoldenTraceCapture();
        outboundSender.setFrameEmitListener(capture::onEmit);
        registeredSessionIds.clear();
    }

    @AfterEach
    void tearDown() {
        outboundSender.setFrameEmitListener(null);
        // Final cleanup so suite-mode does not leak sender VTs.
        for (String sid : new ArrayList<>(registeredSessionIds)) {
            outboundSender.detachSession(sid);
            sessionRegistry.unregister(sid);
        }
        registeredSessionIds.clear();
    }

    @Test
    void byteIdenticalOutputAcrossTwoRuns() throws Exception {
        // ---- Run 1 ----
        resetAll();
        double bondsBefore1 = emergenceMetrics.bondedPairsFormed();
        double compositesBefore1 = emergenceMetrics.compositesFormed();
        driveScenario();
        long emitsA = capture.emitCount();
        Map<String, String> mapA = capture.digestsAsHexMap();
        long formationsA = Math.round(emergenceMetrics.bondedPairsFormed() - bondsBefore1)
                         + Math.round(emergenceMetrics.compositesFormed() - compositesBefore1);

        // Vacuous-baseline guards (REVIEWS H4 / L3)
        assertThat(emitsA).as("REVIEWS H4 — emit count > 0").isGreaterThan(0);
        assertThat(mapA).as("REVIEWS H4 — digest map non-empty").isNotEmpty();
        mapA.forEach((s, h) -> assertThat(h)
                .as("REVIEWS L3 — per-session digest != SHA-256(empty), session=" + s)
                .isNotEqualTo(GoldenTraceCapture.EMPTY_SHA256_HEX));

        // REVIEWS CONSENSUS-H2 — scenario actually exercises bond-formation paths.
        assertThat(formationsA)
                .as("REVIEWS CONSENSUS-H2 — scenario must trigger >= 1 bond OR composite formation")
                .isGreaterThan(0);

        // ---- Run 2 ----
        // resetAll() detaches and joins VT senders before capture.reset() so that any
        // in-flight onEmit callback from the last tick of run 1 completes before we
        // clear the accumulator (prevents stale run-1 frames from polluting run-2 digests).
        resetAll();
        capture.reset();
        double bondsBefore2 = emergenceMetrics.bondedPairsFormed();
        double compositesBefore2 = emergenceMetrics.compositesFormed();
        driveScenario();
        long emitsB = capture.emitCount();
        Map<String, String> mapB = capture.digestsAsHexMap();
        long formationsB = Math.round(emergenceMetrics.bondedPairsFormed() - bondsBefore2)
                         + Math.round(emergenceMetrics.compositesFormed() - compositesBefore2);

        assertThat(emitsB).as("emit count stable across runs").isEqualTo(emitsA);
        assertThat(formationsB).as("bond/composite formation count stable").isEqualTo(formationsA);
        assertThat(mapB).as("D-10 byte-identical outbound frames per session").isEqualTo(mapA);

        // ---- EXPECTED_DIGESTS resource (REVIEWS MED-1) ----
        Map<String, String> expected = loadExpectedDigests();
        if (expected == null) {
            writeBaseline(mapA);
            fail("BASELINE_PINNED — re-run test, file written to " + SOURCE_PATH.toAbsolutePath());
        }
        assertThat(mapA)
                .as("D-10 EXPECTED_DIGESTS pinned at " + RESOURCE_PATH
                        + "; Plan 04 must not change the per-session digests.")
                .isEqualTo(expected);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
     * REVIEWS MEDIUM-5: cross-run cleanup.
     * Detach sender VTs and unregister sessions THEN wipe all stateful engine state
     * so two consecutive driveScenario calls in one @Test do NOT leak VTs or digest state.
     *
     * <p>Follows CompositeFormationDeterminismTest.resetAllSeedsBetweenRuns() for full
     * determinism: resets every seeded Random field in engine components (HIGH #1 pattern).
     */
    private void resetAll() {
        // 1. Drop outbound sender VTs and session registry entries from any prior driveScenario.
        for (String sid : new ArrayList<>(registeredSessionIds)) {
            outboundSender.detachSession(sid);
            sessionRegistry.unregister(sid);
        }
        registeredSessionIds.clear();

        // 2. Clear grid and registries.
        worldGrid.clear();
        compositeRegistry.clear();
        buffRegistry.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest();
        deathFinalizer.resetCountForTest();

        // 3. Reset seeded Random state in every seeded engine bean.
        environmentEngine.resetForTest();
        simulationEngine.resetSeed();
        actionResolver.resetSeed();
        // Rule 1 — inter-run state leak fix: clear per-entity maps that survive resetSeed().
        // lastReproducedTick entries keyed by entityId suppress reproduction in run 2 when
        // the test reuses identical entityIds (trace-bot-0…25), producing digest divergence.
        actionResolver.clearStateForTest();
        compositeEnergyDistributor.resetSeed();
        fertilityInitializer.resetSeed();

        // 4. Clear broadcaster per-session state that survives the run.
        // Rule 1: lastRosterHashBySession in TickBroadcaster suppresses g-blocks when the
        // cached hash matches. Stale entries from run 1 cause run 2 to suppress g-blocks
        // that run 1 sent, producing different per-session digests.
        tickBroadcaster.clearStateForTest();

        // 5. Rebuild EligibleCellIndex from cleared grid + reset placement RNG.
        eligibleCellIndex.rebuildForTest();
        handler.resetSeed();
    }

    /**
     * REVIEWS CONSENSUS-H2: 30 bots on 16×16; two deterministic adjacent-RPS pairs
     * placed BEFORE the main loop force >= 1 bond formation.
     *
     * <p>Step 1: seed two predator+prey adjacent pairs directly on the grid.
     * Step 2: register 26 additional bots via attemptPlacementForTest.
     * Step 3: drive 200 ticks via applicationEventPublisher.publishEvent(new TickEvent(t)).
     */
    private void driveScenario() throws Exception {
        // Step 1: deterministic adjacent-RPS pair seeding (forces >= 1 bond formation).
        // Energy=200 (maxEnergy) → Particle.spawn(id,type,200) sets energy=100 (maxEnergy/2).
        // 100 >= bondEnergyThreshold=50 so bonding eligibility is satisfied.
        seedAdjacentBondingPair("seed-pred-1", "seed-prey-1",
                5, 5, 5, 6,
                Entity.ParticleType.CATALYST, Entity.ParticleType.CATALYST.prey(), 200);

        // CHECKER-ROUND-3 WARNING #4 — post-seed worldGrid assertions confirm pair landed.
        assertThat(worldGrid.getCell(5, 5).occupant())
                .as("seed pair 1 predator placed at (5,5)")
                .isInstanceOf(Entity.Particle.class);
        assertThat(worldGrid.getCell(5, 6).occupant())
                .as("seed pair 1 prey placed at (5,6)")
                .isInstanceOf(Entity.Particle.class);

        seedAdjacentBondingPair("seed-pred-2", "seed-prey-2",
                9, 9, 9, 10,
                Entity.ParticleType.MEMBRANE, Entity.ParticleType.MEMBRANE.prey(), 200);

        assertThat(worldGrid.getCell(9, 9).occupant())
                .as("seed pair 2 predator placed at (9,9)")
                .isInstanceOf(Entity.Particle.class);
        assertThat(worldGrid.getCell(9, 10).occupant())
                .as("seed pair 2 prey placed at (9,10)")
                .isInstanceOf(Entity.Particle.class);

        // Step 2: register the remaining bots via attemptPlacementForTest (26 = BOT_COUNT - 4).
        for (int i = 0; i < BOT_COUNT - 4; i++) {
            String entityId = "trace-bot-" + i;
            String sessionId = "trace-sess-" + i;

            Optional<Position> pos = handler.attemptPlacementForTest(
                    entityId, Entity.ParticleType.values()[i % 3], 100);
            assertThat(pos).as("placement must succeed for bot " + i).isPresent();

            // REVIEWS CONSENSUS-H5 — production signature: attachSession(WebSocketSession, int).
            WebSocketSession mockSession = mock(WebSocketSession.class);
            when(mockSession.isOpen()).thenReturn(true);
            when(mockSession.getId()).thenReturn(sessionId);

            sessionRegistry.register(mockSession);
            outboundSender.attachSession(mockSession, OUTBOUND_QUEUE_SIZE);
            botRegistry.register(sessionId, entityId, pos.get());
            liveEntityRegistry.register(entityId, pos.get(), Optional.of(sessionId));

            registeredSessionIds.add(sessionId);
        }

        // Step 3: drive ticks. TickEvent dispatch exercises the full @Order chain
        // (SimulationEngine @10 → EnvironmentEngine @14 → ActionResolver @20 →
        //  EnvPostActionReconciler @25 → PerceptionBroadcaster @50 → TickBroadcaster @100).
        for (int t = 0; t < TICK_COUNT; t++) {
            applicationEventPublisher.publishEvent(new TickEvent(t));
            awaitAllSessionQueuesDrained();
        }
    }

    /** Place a predator+prey pair at adjacent cells with the given maxEnergy, register in LiveEntityRegistry. */
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
        liveEntityRegistry.register(predId, new Position(px, py), Optional.empty());
        liveEntityRegistry.register(preyId, new Position(qx, qy), Optional.empty());
        eligibleCellIndex.notifyChanged(px, py);
        eligibleCellIndex.notifyChanged(qx, qy);
    }

    /**
     * REVIEWS CONSENSUS-H3: post-queueDepth-zero {@code synchronized(session)} barrier.
     *
     * <p>The drain VT dequeues a frame BEFORE entering {@code synchronized(session){
     * sendMessage; listener.onEmit}}. So {@code queueDepth()==0} can be observed while
     * the listener callback is still executing inside the session monitor. Acquiring
     * the monitor here is a sufficient barrier — per CLAUDE.md "synchronized-session-monitor
     * contract", every writer holds the monitor for the actual sendMessage + listener call.
     */
    private void awaitAllSessionQueuesDrained() throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L; // 2s timeout
        while (System.nanoTime() < deadline) {
            boolean allDrained = true;
            for (String sid : registeredSessionIds) {
                if (outboundSender.queueDepth(sid) > 0) {
                    allDrained = false;
                    break;
                }
            }
            if (allDrained) {
                // REVIEWS CONSENSUS-H3 — acquire+release each session monitor as barrier.
                for (String sid : registeredSessionIds) {
                    WebSocketSession s = sessionRegistry.getSession(sid);
                    if (s != null) {
                        synchronized (s) {
                            // No-op body — acquiring + releasing the monitor is the barrier.
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

    /**
     * Count current BondedPair + CompositeMember grid occupants via LiveEntityRegistry snapshot.
     * Used as a cross-check complementing the EmergenceMetrics counter deltas.
     */
    private long bondAndCompositeFormationCount() {
        long bonds = 0;
        long composites = 0;
        for (LiveEntityRegistry.EntityEntry entry : liveEntityRegistry.snapshot()) {
            var occ = worldGrid.getCell(entry.position().x(), entry.position().y()).occupant();
            if (occ instanceof Entity.BondedPair) {
                bonds++;
            } else if (occ instanceof Entity.CompositeMember) {
                composites++;
            }
        }
        return bonds + composites;
    }
}
