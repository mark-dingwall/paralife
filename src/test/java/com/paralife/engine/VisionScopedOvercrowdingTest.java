package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages.CellView;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Cell;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 14-05 Task 1: vision-scoped overcrowding (D-40) + cycle-6 MEDIUM #9
 * per-bot OVERCROWDED-bit recomposition.
 *
 * <p>Drives the static helper {@link PerceptionBroadcaster#computeVisionScopedOvercrowded}
 * directly (no Spring context) and the per-bot {@code cellToViewForTest} seam.
 *
 * <p>Reads the threshold value from {@code SimulationConfig.defaults().overcrowdingThreshold()}
 * — NOT a public static constant. The broadcaster reads the LIVE
 * {@code simulationConfig.overcrowdingThreshold()} at runtime so yaml overrides
 * take effect without recompile.
 */
class VisionScopedOvercrowdingTest {

    private WorldGrid worldGrid;
    private PerceptionBroadcaster broadcaster;
    private EnvironmentEngine envEngineMock;
    private BuffRegistry buffRegistry;
    private SimulationConfig simulationConfig;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        buffRegistry = new BuffRegistry();
        simulationConfig = SimulationConfig.defaults();
        envEngineMock = Mockito.mock(EnvironmentEngine.class);
        // Default env reads — 0 for any cell / entity. Individual tests override.
        Mockito.when(envEngineMock.getCellStatus(Mockito.any())).thenReturn((byte) 0);
        Mockito.when(envEngineMock.getEntityStatus(Mockito.anyString())).thenReturn((byte) 0);
        broadcaster = new PerceptionBroadcaster(new BotRegistry(), new SessionRegistry(),
                worldGrid, new ObjectMapper(), new CompositeRegistry(),
                envEngineMock, buffRegistry, simulationConfig);
    }

    @Test
    void defaultThresholdMatchesSimulationConfigValue() {
        // The documented-default constant on SimulationEngine must agree with
        // SimulationConfig.defaults() — guards against silent drift.
        assertThat(SimulationEngine.OVERCROWDED_THRESHOLD_DEFAULT)
                .as("cycle-3: OVERCROWDED_THRESHOLD_DEFAULT is a documented default, not a runtime source")
                .isEqualTo(SimulationConfig.defaults().overcrowdingThreshold())
                .isEqualTo(6);
    }

    @Test
    void visionScopedOvercrowdedBitSetWhenVisibleNeighborsDense() {
        // Fill 6 neighbours of (10, 10) within vision radius 2 — threshold=6 → overcrowded.
        placeFiller(10, 9);
        placeFiller(10, 11);
        placeFiller(9, 10);
        placeFiller(11, 10);
        placeFiller(9, 9);
        placeFiller(11, 11);
        int threshold = SimulationConfig.defaults().overcrowdingThreshold();
        boolean result = PerceptionBroadcaster.computeVisionScopedOvercrowded(
                worldGrid, new Position(10, 10), new Position(10, 10), /*radius*/ 2, threshold);
        assertThat(result).as("6 visible neighbours equals threshold=6 → overcrowded").isTrue();
    }

    @Test
    void notOvercrowdedWhenNotEnoughVisibleNeighbors() {
        // Only 2 neighbours — below threshold=6.
        placeFiller(10, 9);
        placeFiller(10, 11);
        int threshold = SimulationConfig.defaults().overcrowdingThreshold();
        boolean result = PerceptionBroadcaster.computeVisionScopedOvercrowded(
                worldGrid, new Position(10, 10), new Position(10, 10), /*radius*/ 2, threshold);
        assertThat(result).isFalse();
    }

    @Test
    void thresholdReadFromLiveConfig() {
        // Prove the predicate respects whatever threshold is passed in — the
        // broadcaster pulls this from simulationConfig.overcrowdingThreshold()
        // at runtime. Here we simulate two different config values.
        placeFiller(10, 9);
        placeFiller(10, 11);
        placeFiller(9, 10);
        // 3 neighbours — overcrowded at threshold 3, not at threshold 6.
        assertThat(PerceptionBroadcaster.computeVisionScopedOvercrowded(
                worldGrid, new Position(10, 10), new Position(10, 10), 2, 3)).isTrue();
        assertThat(PerceptionBroadcaster.computeVisionScopedOvercrowded(
                worldGrid, new Position(10, 10), new Position(10, 10), 2,
                SimulationConfig.defaults().overcrowdingThreshold())).isFalse();
    }

    @Test
    void overcrowdedBitMaskedFromCacheAndRecomputedPerBot() {
        // cycle-6 MEDIUM #9: env cache may carry a GLOBAL OVERCROWDED bit;
        // broadcaster MUST mask bit 0 out and OR in the per-bot value. A
        // globally-overcrowded cell that is NOT overcrowded in a particular
        // bot's vision must present as cellStatus bit 0 = 0 for that bot.
        //
        // Non-bit-0 cache bits (TOXIN_PRESENT=0x02, MUTAGEN_ZONE=0x04) pass
        // through untouched per D-38 bit layout.
        Position cell = new Position(10, 10);
        byte cached = (byte) 0xFF;  // all bits on — globally overcrowded + every env bit
        Mockito.when(envEngineMock.getCellStatus(cell)).thenReturn(cached);
        // Bot position with no dense neighbours in its vision.
        Position botPos = new Position(10, 10);
        CellView view = broadcaster.cellToViewForTest(10, 10, botPos, /*radius*/ 2);
        byte status = view.cellStatus();
        assertThat(status & 0x01)
                .as("cycle-6 MEDIUM #9: cached OVERCROWDED bit masked; per-bot bit OR'd in")
                .isEqualTo(0);
        // TOXIN_PRESENT (bit 1, 0x02) survives the OVERCROWDED strip (D-38).
        assertThat(status & EnvironmentEngine.CELL_STATUS_TOXIN_PRESENT)
                .as("TOXIN_PRESENT preserved from cache (bit 1, non-colliding per D-38)")
                .isEqualTo(EnvironmentEngine.CELL_STATUS_TOXIN_PRESENT);
        // Preserved cache bits on bit 2 (MUTAGEN_ZONE = 0x04).
        assertThat(status & EnvironmentEngine.CELL_STATUS_MUTAGEN_ZONE)
                .as("MUTAGEN_ZONE preserved from cache (bit 2)")
                .isEqualTo(EnvironmentEngine.CELL_STATUS_MUTAGEN_ZONE);
    }

    @Test
    void perBotOvercrowdedBitOredInWhenVisionDense() {
        // Dense neighbourhood → overcrowded per the bot.
        placeFiller(10, 9);
        placeFiller(10, 11);
        placeFiller(9, 10);
        placeFiller(11, 10);
        placeFiller(9, 9);
        placeFiller(11, 11);
        Position cell = new Position(10, 10);
        // Cache reports NOT-overcrowded globally (bit 0 clear).
        Mockito.when(envEngineMock.getCellStatus(cell)).thenReturn((byte) 0x00);
        CellView view = broadcaster.cellToViewForTest(10, 10, new Position(10, 10), /*radius*/ 2);
        assertThat(view.cellStatus() & 0x01)
                .as("vision-scoped neighbours exceed threshold → per-bot OVERCROWDED bit set")
                .isEqualTo(0x01);
    }

    private void placeFiller(int x, int y) {
        worldGrid.setEntity(x, y, Particle.spawn("f-" + x + "-" + y, ParticleType.CATALYST));
    }
}
