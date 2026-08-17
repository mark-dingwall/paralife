package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paralife.codec.Frame;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.*;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * Plan 20.1-02 (Task 1 TDD RED): D-02 REPRODUCER server-side auto-place.
 *
 * <p>Tests pin the D-02 contract:
 * <ul>
 *   <li>Nearest-free-cell placement (distance-ordered, not first-found)</li>
 *   <li>Dual-run determinism</li>
 *   <li>Tie-break: lower-(dy, dx) cell wins when equidistant</li>
 *   <li>Edge-adjacent REPRODUCER: spawn-wrap via {@code Math.floorMod} + dual-run determinism</li>
 *   <li>Bounded skip: no spawn, no exception, pool energy UNCHANGED when neighbourhood full</li>
 * </ul>
 *
 * <p>These tests are RED until Task 2 implements {@code nearestFreeCell} in ActionResolver.
 */
class ReproducerAutoPlaceTest {

    /** Grid size — large enough to avoid corner effects for most tests; 16×16 matches prod defaults. */
    private static final int DIM = 16;

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private CompositeRegistry compositeRegistry;
    private CompositeConfig compositeConfig;
    private SimulationConfig config;
    private ActionResolver resolver;
    private SpeciesSpawnCounter budSpawnCounter;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(DIM, DIM));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        compositeRegistry = new CompositeRegistry();
        compositeConfig = CompositeConfig.defaults();
        config = SimulationConfig.defaults();
        resolver = new ActionResolver(worldGrid, botRegistry, sessionRegistry, config,
                compositeRegistry, compositeConfig, legacyProfile());
        budSpawnCounter = new SpeciesSpawnCounter();
        resolver.setSpawnCounter(budSpawnCounter);
    }

    // ── Fixtures ─────────────────────────────────────────────────

    /** MetabolicProfile with reproduceCost = REPRODUCE_ENERGY_COST (30), starvationThreshold=0. */
    private static MetabolicProfile legacyProfile() {
        MetabolicProfile.TypeProfile p = new MetabolicProfile.TypeProfile(
                /* maxEnergy */ 100,
                /* decayPerTick */ 1,
                /* combatEnergyTransfer */ 10,
                /* attackPower */ 10,
                /* nutrientConsumeEnergy */ 5,
                /* reproduceEnergyCost */ ActionResolver.REPRODUCE_ENERGY_COST,
                /* reproduceCooldown */ 0,
                /* bonusOffspringChance */ 0.0,
                /* reproduceRange */ 1,
                /* starvationThreshold */ 0,
                /* starvationFloor */ 0);
        return new MetabolicProfile(p, p, p);
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);
        return session;
    }

    private void placeReproducer(String sessionId, String entityId, String compositeId,
                                  Position pos) {
        CompositeMember cm = new CompositeMember(entityId, compositeId,
                ParticleType.SPORE, Role.REPRODUCER, 50, 100);
        worldGrid.setEntity(pos.x(), pos.y(), cm);
        botRegistry.register(sessionId, entityId, pos);
    }

    private void registerCompositeWithEnergy(String compositeId, String memberId,
                                              Position pos, int energy) {
        var memberIds = List.of(memberId);
        var positions = Map.of(memberId, pos);
        // maxPoolEnergy=200 so starvation floor stays 0 (threshold=0)
        compositeRegistry.register(compositeId, memberIds, positions, energy, 200);
    }

    /** Reproduce verb — direction arg is ignored by server after D-02, but still sent. */
    private static Frame.ActionFrame reproduce(char numpad) {
        return new Frame.ActionFrame('R', Optional.of(String.valueOf(numpad)));
    }

    /** Place a blocking Particle to occupy the given cell. */
    private void occupy(int x, int y) {
        worldGrid.setEntity(x, y, new Particle("blocker-" + x + "-" + y,
                ParticleType.CATALYST, 10, 40));
    }

    // ── Tests ────────────────────────────────────────────────────

    /**
     * Nearest-free placement: distance-ordered, not first-found.
     *
     * <p>REPRODUCER at (5,5). All cells at Euclidean distance ≤ 2 (within the
     * 2-step box) are occupied so the first iterated cell is NOT the nearest
     * unblocked cell. The expected nearest free cell is at distance √5
     * (offset (1,2) or (2,1) — we leave exactly one free at (6,7) = offset (1,2)).
     * Cells at distance 1 and √2 are all occupied, proving the implementation
     * must sort by distance, not iterate in natural offset order.
     */
    @Test
    void nearestFreePlacement_isDistanceOrdered_notFirstFound() {
        Position reproducerPos = new Position(5, 5);
        mockSession("s-rep");
        placeReproducer("s-rep", "cm-rep", "comp1", reproducerPos);
        registerCompositeWithEnergy("comp1", "cm-rep", reproducerPos,
                ActionResolver.REPRODUCE_ENERGY_COST + 10);

        // Occupy all distance-1 orthogonal (4 cells)
        occupy(5, 6); // (0,+1)
        occupy(5, 4); // (0,-1)
        occupy(6, 5); // (+1,0)
        occupy(4, 5); // (-1,0)

        // Occupy all distance-√2 diagonal (4 cells)
        occupy(6, 6); // (+1,+1)
        occupy(4, 4); // (-1,-1)
        occupy(6, 4); // (+1,-1)
        occupy(4, 6); // (-1,+1)

        // At distance 2 (straight): (5,7),(5,3),(7,5),(3,5) — block all of those too
        occupy(5, 7); // (0,+2)
        occupy(5, 3); // (0,-2)
        occupy(7, 5); // (+2,0)
        occupy(3, 5); // (-2,0)

        // Distance √5 candidates (offset magnitude² = 5):
        // (1,2),(2,1),(-1,2),(2,-1),(1,-2),(-2,1),(-1,-2),(-2,-1)
        // Block all except (1,2) -> (6,7)
        occupy(7, 6); // (+2,+1)
        occupy(6, 3); // (+1,-2)
        occupy(4, 7); // (-1,+2)
        occupy(3, 6); // (-2,+1)
        occupy(3, 4); // (-2,-1)
        occupy(4, 3); // (-1,-2)
        occupy(7, 4); // (+2,-1)
        // Leave (6,7) = offset(+1,+2) free — distance² = 5

        // Act
        long before = budSpawnCounter.get(ParticleType.SPORE);
        resolver.resolveActions(1, Map.of("s-rep", reproduce('5')));

        // The bud must land on (6,7) — the nearest unblocked cell
        assertThat(worldGrid.getCell(6, 7).occupant())
                .as("bud should spawn at nearest free cell (6,7) — not a first-found cell")
                .isInstanceOf(Particle.class);
        assertThat(budSpawnCounter.get(ParticleType.SPORE) - before)
                .as("O4: committed bud increments its species by exactly 1").isEqualTo(1L);
    }

    /**
     * Dual-run determinism: identical setups produce the same bud cell across two
     * independent resolution calls.
     */
    @Test
    void dualRun_determinism_sameSetupSameBudCell() {
        Position reproducerPos = new Position(4, 4);

        // Run 1: fresh resolver + grid
        WorldGrid grid1 = new WorldGrid(new GridConfig(DIM, DIM));
        BotRegistry bots1 = new BotRegistry();
        SessionRegistry sess1 = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        CompositeRegistry creg1 = new CompositeRegistry();

        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.getId()).thenReturn("s1");
        when(ws1.isOpen()).thenReturn(true);
        sess1.register(ws1);

        CompositeMember cm1 = new CompositeMember("cm1", "comp1", ParticleType.SPORE,
                Role.REPRODUCER, 50, 100);
        grid1.setEntity(4, 4, cm1);
        bots1.register("s1", "cm1", reproducerPos);
        creg1.register("comp1", List.of("cm1"), Map.of("cm1", reproducerPos),
                ActionResolver.REPRODUCE_ENERGY_COST + 10, 200);

        // Block (4,5) so the tie-break picks a deterministic second-best
        grid1.setEntity(4, 5, new Particle("b1", ParticleType.CATALYST, 10, 40));

        ActionResolver res1 = new ActionResolver(grid1, bots1, sess1, config,
                creg1, compositeConfig, legacyProfile());
        res1.resolveActions(1, Map.of("s1", reproduce('5')));

        // Find where the bud landed in run 1
        Position budRun1 = findBudPosition(grid1, reproducerPos);
        assertThat(budRun1).as("bud must spawn somewhere in run 1").isNotNull();

        // Run 2: identical setup
        WorldGrid grid2 = new WorldGrid(new GridConfig(DIM, DIM));
        BotRegistry bots2 = new BotRegistry();
        SessionRegistry sess2 = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        CompositeRegistry creg2 = new CompositeRegistry();

        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.getId()).thenReturn("s2");
        when(ws2.isOpen()).thenReturn(true);
        sess2.register(ws2);

        CompositeMember cm2 = new CompositeMember("cm2", "comp2", ParticleType.SPORE,
                Role.REPRODUCER, 50, 100);
        grid2.setEntity(4, 4, cm2);
        bots2.register("s2", "cm2", reproducerPos);
        creg2.register("comp2", List.of("cm2"), Map.of("cm2", reproducerPos),
                ActionResolver.REPRODUCE_ENERGY_COST + 10, 200);

        grid2.setEntity(4, 5, new Particle("b2", ParticleType.CATALYST, 10, 40));

        ActionResolver res2 = new ActionResolver(grid2, bots2, sess2, config,
                creg2, compositeConfig, legacyProfile());
        res2.resolveActions(1, Map.of("s2", reproduce('5')));

        Position budRun2 = findBudPosition(grid2, reproducerPos);
        assertThat(budRun2).as("bud must spawn somewhere in run 2").isNotNull();

        assertThat(budRun1).as("identical setups must produce the same bud cell").isEqualTo(budRun2);
    }

    /**
     * Tie-break: when two candidates are equidistant, the (dx²+dy², dy, dx) raw
     * sort picks the cell with the lower-dy (then lower-dx) offset.
     *
     * <p>REPRODUCER at (6,6). Block all distance-1 cells and two of the four distance-√2
     * diagonals, leaving exactly TWO equidistant candidates free that differ in BOTH
     * coordinates so the (dy,dx) ordering can be distinguished from (dx,dy):
     * <ul>
     *   <li>offset (+1,-1) → (7,5): dy=-1, dx=+1</li>
     *   <li>offset (-1,+1) → (5,7): dy=+1, dx=-1</li>
     * </ul>
     * The correct (dy,dx) comparator picks (7,5) (dy=-1 &lt; +1). A buggy (dx,dy) comparator
     * would pick (5,7) (dx=-1 &lt; +1). Asserting (7,5) occupied AND (5,7) empty catches the
     * wrong ordering — which the prior single-survivor version could not.
     */
    @Test
    void tieBreak_lowerDyThenLowerDx_wins() {
        Position reproducerPos = new Position(6, 6);
        mockSession("s-tie");
        placeReproducer("s-tie", "cm-tie", "comptie", reproducerPos);
        registerCompositeWithEnergy("comptie", "cm-tie", reproducerPos,
                ActionResolver.REPRODUCE_ENERGY_COST + 10);

        // Block orthogonal distance-1 cells
        occupy(6, 7); // (0,+1)
        occupy(6, 5); // (0,-1)
        occupy(7, 6); // (+1,0)
        occupy(5, 6); // (-1,0)

        // At distance √2, block two diagonals; leave (7,5) and (5,7) free (differ in both coords).
        occupy(7, 7); // (+1,+1)
        occupy(5, 5); // (-1,-1)

        resolver.resolveActions(1, Map.of("s-tie", reproduce('5')));

        // Correct (dy,dx) ordering: (+1,-1)→(7,5) wins (dy=-1 < +1). A (dx,dy) impl would pick (5,7).
        assertThat(worldGrid.getCell(7, 5).occupant())
                .as("tie-break: (dy=-1,dx=+1)→(7,5) beats (dy=+1,dx=-1)→(5,7) by lower dy first")
                .isInstanceOf(Particle.class);
        assertThat(worldGrid.getCell(5, 7).occupant())
                .as("(5,7) must remain empty — a (dx,dy) ordering bug would place the bud here")
                .isNull();
    }

    /**
     * Edge-adjacent determinism + spawn-wrap: a REPRODUCER placed near the torus
     * seam so that the nearest free cell is on the other side of the wrap.
     *
     * <p>REPRODUCER at (0,0) — on the seam. Block the two non-wrapping distance-1
     * neighbours (1,0) and (0,1). The two remaining distance-1 candidates are reached
     * only via {@code Math.floorMod}: offset (-1,0)→(15,0) and offset (0,-1)→(0,15).
     * The (dy,dx) comparator picks (0,15) (dy=-1 &lt; 0). We assert the bud lands at that
     * exact wrapped cell — a subtraction-based impl (origin.y-1 = -1, no floorMod) would
     * produce an invalid/different cell, so this genuinely pins floorMod correctness
     * rather than only dual-run determinism.
     */
    @Test
    void edgeAdjacent_spawnWrap_deterministic() {
        // REPRODUCER at (0,0) — the corner seam, so nearest free cells wrap.
        Position reproducerPos = new Position(0, 0);

        // Run 1
        WorldGrid grid1 = new WorldGrid(new GridConfig(DIM, DIM));
        BotRegistry bots1 = new BotRegistry();
        SessionRegistry sess1 = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        CompositeRegistry creg1 = new CompositeRegistry();

        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.getId()).thenReturn("es1");
        when(ws1.isOpen()).thenReturn(true);
        sess1.register(ws1);

        CompositeMember cm1 = new CompositeMember("ecm1", "ecomp1", ParticleType.SPORE,
                Role.REPRODUCER, 50, 100);
        grid1.setEntity(0, 0, cm1);
        bots1.register("es1", "ecm1", reproducerPos);
        creg1.register("ecomp1", List.of("ecm1"), Map.of("ecm1", reproducerPos),
                ActionResolver.REPRODUCE_ENERGY_COST + 10, 200);

        // Block the two non-wrapping distance-1 cells: (1,0)=offset(+1,0) and (0,1)=offset(0,+1).
        grid1.setEntity(1, 0, new Particle("eb1", ParticleType.CATALYST, 10, 40));
        grid1.setEntity(0, 1, new Particle("eb2", ParticleType.CATALYST, 10, 40));
        // Remaining distance-1 candidates both wrap: (-1,0)→(15,0) and (0,-1)→(0,15).
        // (dy,dx) ordering picks (0,15) (dy=-1 < 0).

        ActionResolver res1 = new ActionResolver(grid1, bots1, sess1, config,
                creg1, compositeConfig, legacyProfile());
        res1.resolveActions(1, Map.of("es1", reproduce('5')));

        Position budRun1 = findBudPosition(grid1, reproducerPos);
        assertThat(budRun1)
                .as("spawn-wrap: bud must land at the floorMod-wrapped cell (0,15)")
                .isEqualTo(new Position(0, 15));

        // Run 2: identical
        WorldGrid grid2 = new WorldGrid(new GridConfig(DIM, DIM));
        BotRegistry bots2 = new BotRegistry();
        SessionRegistry sess2 = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        CompositeRegistry creg2 = new CompositeRegistry();

        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.getId()).thenReturn("es2");
        when(ws2.isOpen()).thenReturn(true);
        sess2.register(ws2);

        CompositeMember cm2 = new CompositeMember("ecm2", "ecomp2", ParticleType.SPORE,
                Role.REPRODUCER, 50, 100);
        grid2.setEntity(0, 0, cm2);
        bots2.register("es2", "ecm2", reproducerPos);
        creg2.register("ecomp2", List.of("ecm2"), Map.of("ecm2", reproducerPos),
                ActionResolver.REPRODUCE_ENERGY_COST + 10, 200);

        grid2.setEntity(1, 0, new Particle("eb3", ParticleType.CATALYST, 10, 40));
        grid2.setEntity(0, 1, new Particle("eb4", ParticleType.CATALYST, 10, 40));

        ActionResolver res2 = new ActionResolver(grid2, bots2, sess2, config,
                creg2, compositeConfig, legacyProfile());
        res2.resolveActions(1, Map.of("es2", reproduce('5')));

        Position budRun2 = findBudPosition(grid2, reproducerPos);
        assertThat(budRun2)
                .as("spawn-wrap: run 2 must also land at (0,15)")
                .isEqualTo(new Position(0, 15));

        assertThat(budRun1)
                .as("edge-adjacent dual-run: identical setup must produce same wrapped bud cell")
                .isEqualTo(budRun2);
    }

    /**
     * Bounded skip: when every cell within MAX_REPRODUCER_SEARCH_RADIUS is occupied,
     * no bud spawns, no exception is thrown, AND the shared-pool energy is UNCHANGED
     * after resolution (reproduce cost is NOT deducted on a skip).
     */
    @Test
    void boundedSkip_noSpawn_poolEnergyUnchanged() {
        // MAX_REPRODUCER_SEARCH_RADIUS = 5; fill all cells in the [-5,5]² box.
        // REPRODUCER at centre of a 16×16 grid: (8,8)
        Position reproducerPos = new Position(8, 8);
        mockSession("s-skip");
        placeReproducer("s-skip", "cm-skip", "compskip", reproducerPos);

        int initialEnergy = ActionResolver.REPRODUCE_ENERGY_COST + 50;
        registerCompositeWithEnergy("compskip", "cm-skip", reproducerPos, initialEnergy);

        // Block every cell in the [-5,5]² box around (8,8) except (8,8) itself
        int radius = 5; // must match ActionResolver.MAX_REPRODUCER_SEARCH_RADIUS
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx == 0 && dy == 0) continue; // the REPRODUCER itself
                int tx = Math.floorMod(8 + dx, DIM);
                int ty = Math.floorMod(8 + dy, DIM);
                // Don't double-place on top of the reproducer cell
                if (tx == 8 && ty == 8) continue;
                if (worldGrid.getCell(tx, ty).isEmpty()) {
                    occupy(tx, ty);
                }
            }
        }

        // Capture pool energy before
        int poolBefore = compositeRegistry.getSharedEnergy("compskip");

        // Act — must not throw
        assertThatNoException().isThrownBy(() ->
                resolver.resolveActions(1, Map.of("s-skip", reproduce('5'))));

        // No bud should exist anywhere outside the reproducer's own cell
        int budCount = 0;
        for (int x = 0; x < DIM; x++) {
            for (int y = 0; y < DIM; y++) {
                if (x == 8 && y == 8) continue;
                if (worldGrid.getCell(x, y).occupant() instanceof Particle p
                        && p.id().startsWith("child-")) {
                    budCount++;
                }
            }
        }
        assertThat(budCount).as("no bud should be spawned when neighbourhood is full").isZero();

        // Pool energy must be unchanged — reproduce cost NOT deducted on skip
        int poolAfter = compositeRegistry.getSharedEnergy("compskip");
        assertThat(poolAfter)
                .as("pool energy must be unchanged after a bounded skip (no cost deducted)")
                .isEqualTo(poolBefore);

        assertThat(budSpawnCounter.get(ParticleType.SPORE))
                .as("O4: a skipped bud commits no birth (failed-path control)").isZero();
    }

    /**
     * Direction arg is ignored: passing a direction that points at an occupied cell
     * still results in placement at the nearest free cell, not a no-op.
     *
     * <p>REPRODUCER at (5,5). numpad '6' = East = (6,5). We occupy (6,5) so the old
     * direction-based impl would skip. The new impl ignores the direction and still
     * places the bud at the nearest free cell.
     */
    @Test
    void directionArgIgnored_placesAtNearestFree_notNoOp() {
        Position reproducerPos = new Position(5, 5);
        mockSession("s-dir");
        placeReproducer("s-dir", "cm-dir", "compdir", reproducerPos);
        registerCompositeWithEnergy("compdir", "cm-dir", reproducerPos,
                ActionResolver.REPRODUCE_ENERGY_COST + 10);

        // Occupy the cell the bot "asks" to bud into (East = (6,5))
        occupy(6, 5);

        // Act with direction '6' = East, which points at the occupied cell
        resolver.resolveActions(1, Map.of("s-dir", reproduce('6')));

        // A bud must have appeared somewhere (not at (6,5), which is blocked)
        boolean budExists = false;
        for (int x = 0; x < DIM; x++) {
            for (int y = 0; y < DIM; y++) {
                if (x == 5 && y == 5) continue;
                if (worldGrid.getCell(x, y).occupant() instanceof Particle p
                        && p.id().startsWith("child-")) {
                    budExists = true;
                }
            }
        }
        assertThat(budExists)
                .as("direction arg is ignored: bud must spawn at nearest free cell even if given direction is blocked")
                .isTrue();

        // Confirm (6,5) still has the blocker (bud did not clobber it)
        assertThat(worldGrid.getCell(6, 5).occupant())
                .as("blocker at (6,5) must remain — bud went elsewhere")
                .isInstanceOf(Particle.class);
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Scan the grid for a child-* Particle not at the reproducer position.
     */
    private Position findBudPosition(WorldGrid grid, Position reproducerPos) {
        for (int x = 0; x < DIM; x++) {
            for (int y = 0; y < DIM; y++) {
                if (x == reproducerPos.x() && y == reproducerPos.y()) continue;
                var occ = grid.getCell(x, y).occupant();
                if (occ instanceof Particle p && p.id().startsWith("child-")) {
                    return new Position(x, y);
                }
            }
        }
        return null;
    }
}
