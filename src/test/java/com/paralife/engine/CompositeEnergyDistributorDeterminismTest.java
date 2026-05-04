package com.paralife.engine;

import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 19.1 D-05: same-seed multi-composite runs must produce byte-exact same
 * energy states across two runs — verifies that {@link CompositeEnergyDistributor#onTick}
 * iterates composites in a stable sorted order before calling
 * {@link java.util.Collections#shuffle} with the seeded {@code compositeRng}.
 *
 * <p>Relocated here per B2.4 amendment: the env-only {@link EnvironmentDeterminismTest}
 * is the env-engine contract surface; composite-energy accounting lives in a separate
 * engine and warrants its own determinism test class.
 */
class CompositeEnergyDistributorDeterminismTest {

    private static final int WIDTH = 16;
    private static final int HEIGHT = 16;

    private WorldGrid grid;
    private CompositeRegistry compositeRegistry;
    private CompositeConfig config;
    private CompositeEnergyDistributor distributor;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
        compositeRegistry = new CompositeRegistry();
        config = CompositeConfig.defaults();
        distributor = new CompositeEnergyDistributor(grid, compositeRegistry, config);
    }

    /**
     * Phase 19.1 D-05: two runs with the same seed and the same 3+ composites
     * must produce byte-exact member-energy snapshots. Before the fix, the CHM
     * iteration order of {@code compositeRegistry.getAll()} caused non-deterministic
     * outer iteration, which changed which composites were healed in which order.
     */
    @Test
    @DisplayName("CE — multi-composite same-seed runs produce byte-exact energy snapshots (D-05)")
    void multiCompositeSameSeedDeterminism() {
        Map<String, Integer[]> run1 = driveMultiCompositeScenario();
        // resetSeed() re-initialises compositeRng from CompositeConfig.seed().
        // Since CompositeConfig.defaults() uses a fixed seed, both runs are identical.
        distributor.resetSeed();
        Map<String, Integer[]> run2 = driveMultiCompositeScenario();

        for (String compositeId : run1.keySet()) {
            Integer[] energies1 = run1.get(compositeId);
            Integer[] energies2 = run2.get(compositeId);
            assertThat(energies2)
                    .as("Phase 19.1 D-05 — composite %s energy must be identical across same-seed runs. "
                            + "If this fails, onTick iterates compositeRegistry.getAll() in CHM order.", compositeId)
                    .containsExactly(energies1);
        }
    }

    /**
     * Set up 3 composites each with 2 members and a shared pool, drive 300 ticks,
     * then capture member energies. The composites are registered with IDs that have
     * non-alphabetical insertion order to make the CHM-order bug observable.
     */
    private Map<String, Integer[]> driveMultiCompositeScenario() {
        // Clear grid + registry between runs
        grid.clear();
        compositeRegistry.clear();

        // Composite IDs chosen so their natural string order ("comp-alpha", "comp-beta", "comp-gamma")
        // differs from insertion order (gamma first) — exposes CHM non-determinism.
        String[] compositeIds = {"comp-gamma", "comp-alpha", "comp-beta"};

        for (int ci = 0; ci < compositeIds.length; ci++) {
            String compositeId = compositeIds[ci];
            String m1 = compositeId + "-m1";
            String m2 = compositeId + "-m2";

            int baseX = ci * 4;
            Position p1 = new Position(baseX, 0);
            Position p2 = new Position(baseX + 1, 0);

            // Place members on grid
            grid.setEntity(p1.x(), p1.y(),
                    new CompositeMember(m1, compositeId, ParticleType.CATALYST, Role.FEEDER, 80, 100));
            grid.setEntity(p2.x(), p2.y(),
                    new CompositeMember(m2, compositeId, ParticleType.CATALYST, Role.DEFENDER, 60, 100));

            compositeRegistry.register(compositeId,
                    List.of(m1, m2),
                    Map.of(m1, p1, m2, p2),
                    50, 200);
        }

        // Drive 300 ticks
        for (int tick = 1; tick <= 300; tick++) {
            distributor.onTick(new TickEvent(tick));
        }

        // Capture energy snapshot for each composite's members
        java.util.Map<String, Integer[]> snapshot = new java.util.TreeMap<>();
        for (String compositeId : compositeIds) {
            CompositeRegistry.CompositeState state = compositeRegistry.getComposite(compositeId).orElse(null);
            if (state == null) {
                snapshot.put(compositeId, new Integer[0]);
                continue;
            }
            List<String> memberIds = new java.util.ArrayList<>(state.getMemberIds());
            java.util.Collections.sort(memberIds); // sort for stable capture
            Integer[] energies = memberIds.stream()
                    .map(mid -> {
                        Position pos = state.getPositionForMember(mid);
                        if (pos == null) return -1;
                        var occ = grid.getCell(pos.x(), pos.y()).occupant();
                        return occ instanceof CompositeMember cm ? cm.energy() : -1;
                    })
                    .toArray(Integer[]::new);
            snapshot.put(compositeId, energies);
        }
        return snapshot;
    }
}
