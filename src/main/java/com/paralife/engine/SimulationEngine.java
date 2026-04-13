package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side physics engine. Processes the grid each tick in four phases:
 * <ol>
 *   <li><b>Interactions</b> — adjacent entities interact: bonding (endosymbiosis) or combat (RPS)</li>
 *   <li><b>Energy decay</b> — all particles and bonded pairs lose energy</li>
 *   <li><b>Death</b> — zero-energy entities removed</li>
 *   <li><b>Nutrient spawning</b> — empty cells may gain nutrients</li>
 * </ol>
 *
 * Runs before the tick broadcaster (Order(10) vs broadcaster's default order)
 * so that broadcasts reflect the post-simulation state.
 */
@Component
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final WorldGrid worldGrid;
    private final SimulationConfig config;
    private final BotRegistry botRegistry;
    private final BondingConfig bondingConfig;
    private final AtomicLong nutrientIdCounter = new AtomicLong(0);
    private final AtomicInteger lastTickBondCount = new AtomicInteger(0);

    public SimulationEngine(WorldGrid worldGrid, SimulationConfig config,
                            BotRegistry botRegistry, BondingConfig bondingConfig) {
        this.worldGrid = worldGrid;
        this.config = config;
        this.botRegistry = botRegistry;
        this.bondingConfig = bondingConfig;
    }

    public int getLastTickBondCount() {
        return lastTickBondCount.get();
    }

    @EventListener
    @Order(10) // Before TickBroadcaster (default order = Integer.MAX_VALUE)
    public void onTick(TickEvent event) {
        if (!config.enabled()) {
            return;
        }
        processTick(event.tickNumber());
    }

    /**
     * Process one simulation tick. Public for direct use in tests.
     */
    public void processTick(long tickNumber) {
        int width = worldGrid.getWidth();
        int height = worldGrid.getHeight();

        // Phase 1: Interaction resolution (bonding or combat)
        int[] interactionCounts = processInteractions(width, height);
        int combatEvents = interactionCounts[0];
        int bondEvents = interactionCounts[1];
        lastTickBondCount.set(bondEvents);

        // Phase 2: Energy decay
        int decayed = processEnergyDecay(width, height);

        // Phase 2.5: Overcrowding penalty
        int overcrowded = processOvercrowding(width, height);

        // Phase 3: Death removal
        int deaths = processDeaths(width, height);

        // Phase 4: Nutrient spawning
        int spawned = processNutrientSpawning(width, height);

        if (log.isDebugEnabled()) {
            log.debug("Tick {} simulation: combat={}, bonds={}, decayed={}, overcrowded={}, deaths={}, nutrients_spawned={}",
                    tickNumber, combatEvents, bondEvents, decayed, overcrowded, deaths, spawned);
        }
    }

    // ── Phase 1: Interactions (combat + bonding) ──────────────────

    private sealed interface InteractionResult {}
    private record CombatDelta(Position pos, int energyDelta) implements InteractionResult {}
    private record BondFormation(Position primaryPos, Position secondaryPos,
                                  Particle predator, Particle prey) implements InteractionResult {}

    /**
     * For each particle, check adjacent cells for interactions:
     * - Predator+prey pair eligible for bonding → form BondedPair
     * - Otherwise → standard RPS combat
     * - Particle attacking BondedPair → probabilistic defense
     *
     * Uses snapshot reads + deferred writes to avoid order-dependent results.
     * Cells are processed in random order to prevent spatial bias.
     *
     * @return int[2]: [combatEvents, bondEvents]
     */
    private int[] processInteractions(int width, int height) {
        // Build list of all particle positions (attackers are always Particles)
        List<Position> particlePositions = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (cell.occupant() instanceof Particle) {
                    particlePositions.add(new Position(x, y));
                }
            }
        }

        if (particlePositions.isEmpty()) return new int[]{0, 0};

        // Shuffle to prevent directional bias
        Collections.shuffle(particlePositions, ThreadLocalRandom.current());

        List<InteractionResult> results = new ArrayList<>();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Position pos : particlePositions) {
            Cell cell = worldGrid.getCell(pos.x(), pos.y());
            if (!(cell.occupant() instanceof Particle attacker)) continue;

            for (Position nPos : worldGrid.getNeighbors(pos.x(), pos.y())) {
                Cell neighborCell = worldGrid.getCell(nPos.x(), nPos.y());
                Entity defender = neighborCell.occupant();

                // Case 1: Particle vs Particle — predator attacks prey
                if (defender instanceof Particle prey && attacker.beats(prey)) {
                    // Check bonding eligibility FIRST (per D-10)
                    if (attacker.energy() >= bondingConfig.bondEnergyThreshold()
                            && prey.energy() >= bondingConfig.bondEnergyThreshold()
                            && rng.nextDouble() < bondingConfig.bondingProbability()) {
                        // Bond outcome (per D-06, D-07, D-08)
                        results.add(new BondFormation(pos, nPos, attacker, prey));
                    } else {
                        // Combat outcome (existing logic)
                        results.add(new CombatDelta(pos, config.combatEnergyTransfer()));
                        results.add(new CombatDelta(nPos, -config.combatEnergyTransfer()));
                    }
                }

                // Case 2: Particle attacks BondedPair (per D-12, D-15)
                if (defender instanceof Entity.BondedPair bp
                        && attacker.type() == bp.primaryType().predator()) {
                    // Defense check: secondary type grants deflection chance
                    if (rng.nextDouble() >= bondingConfig.bondDefenseChance()) {
                        // Not deflected — normal combat exchange
                        results.add(new CombatDelta(pos, config.combatEnergyTransfer()));
                        results.add(new CombatDelta(nPos, -config.combatEnergyTransfer()));
                    }
                    // If deflected (roll < bondDefenseChance), no deltas added
                }
            }
        }

        // Apply results — combat deltas first, then bond formations
        int combatEvents = 0;
        int bondEvents = 0;
        Set<Position> claimedForBonding = new HashSet<>();

        // Apply combat deltas
        for (InteractionResult result : results) {
            if (result instanceof CombatDelta delta) {
                Cell c = worldGrid.getCell(delta.pos.x(), delta.pos.y());
                if (c.occupant() instanceof Particle p) {
                    worldGrid.setEntity(delta.pos.x(), delta.pos.y(),
                            p.withEnergy(p.energy() + delta.energyDelta));
                    combatEvents++;
                } else if (c.occupant() instanceof Entity.BondedPair bp) {
                    worldGrid.setEntity(delta.pos.x(), delta.pos.y(),
                            bp.withEnergy(bp.energy() + delta.energyDelta));
                    combatEvents++;
                }
            }
        }

        // Apply bond formations (per D-08 — deferred, with double-bond protection)
        for (InteractionResult result : results) {
            if (result instanceof BondFormation bond) {
                // Guard: positions must not have been claimed by another bond this tick
                if (claimedForBonding.contains(bond.secondaryPos)
                        || claimedForBonding.contains(bond.primaryPos)) {
                    continue;
                }
                Cell primaryCell = worldGrid.getCell(bond.primaryPos.x(), bond.primaryPos.y());
                Cell secondaryCell = worldGrid.getCell(bond.secondaryPos.x(), bond.secondaryPos.y());
                if (!(primaryCell.occupant() instanceof Particle)
                        || !(secondaryCell.occupant() instanceof Particle)) {
                    continue;
                }
                // Create BondedPair (per D-05, D-06, D-07)
                Entity.BondedPair bondedPair = new Entity.BondedPair(
                        bond.predator.id() + "+" + bond.prey.id(),
                        bond.predator.type(),   // primary = predator
                        bond.prey.type(),        // secondary = prey
                        bond.predator.energy() + bond.prey.energy(),
                        bond.predator.maxEnergy() + bond.prey.maxEnergy(),
                        bond.predator.id(),      // primaryEntityId for bot cleanup
                        bond.prey.id()           // secondaryEntityId for bot cleanup
                );
                worldGrid.setEntity(bond.primaryPos.x(), bond.primaryPos.y(), bondedPair);
                worldGrid.clearEntity(bond.secondaryPos.x(), bond.secondaryPos.y());
                claimedForBonding.add(bond.primaryPos);
                claimedForBonding.add(bond.secondaryPos);
                bondEvents++;
            }
        }

        return new int[]{combatEvents / 2, bondEvents};
    }

    // ── Phase 2: Energy decay ──────────────────────────────────────

    private int processEnergyDecay(int width, int height) {
        if (config.energyDecayPerTick() == 0) return 0;

        int decayed = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (cell.occupant() instanceof Particle p) {
                    Particle updated = p.withEnergy(p.energy() - config.energyDecayPerTick());
                    worldGrid.setEntity(x, y, updated);
                    decayed++;
                } else if (cell.occupant() instanceof Entity.BondedPair bp) {
                    Entity.BondedPair updated = bp.withEnergy(bp.energy() - config.energyDecayPerTick());
                    worldGrid.setEntity(x, y, updated);
                    decayed++;
                }
            }
        }
        return decayed;
    }

    // ── Phase 2.5: Overcrowding ─────────────────────────────────────

    private int processOvercrowding(int width, int height) {
        if (config.overcrowdingThreshold() > 8 || config.overcrowdingEnergyPenalty() == 0) return 0;

        int overcrowded = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                Entity occupant = cell.occupant();
                if (!(occupant instanceof Particle) && !(occupant instanceof Entity.BondedPair)) continue;

                int neighborCount = 0;
                for (Position nPos : worldGrid.getNeighbors(x, y)) {
                    Entity neighbor = worldGrid.getCell(nPos.x(), nPos.y()).occupant();
                    if (neighbor instanceof Particle || neighbor instanceof Entity.BondedPair) {
                        neighborCount++;
                    }
                }

                if (neighborCount >= config.overcrowdingThreshold()) {
                    if (occupant instanceof Particle p) {
                        worldGrid.setEntity(x, y, p.withEnergy(p.energy() - config.overcrowdingEnergyPenalty()));
                    } else if (occupant instanceof Entity.BondedPair bp) {
                        worldGrid.setEntity(x, y, bp.withEnergy(bp.energy() - config.overcrowdingEnergyPenalty()));
                    }
                    if (!cell.hasFlag(Cell.FLAG_OVERCROWDED)) {
                        worldGrid.setCell(x, y, worldGrid.getCell(x, y).withAddedFlag(Cell.FLAG_OVERCROWDED));
                    }
                    overcrowded++;
                } else if (cell.hasFlag(Cell.FLAG_OVERCROWDED)) {
                    worldGrid.setCell(x, y, cell.withRemovedFlag(Cell.FLAG_OVERCROWDED));
                }
            }
        }
        return overcrowded;
    }

    // ── Phase 3: Death removal ─────────────────────────────────────

    private int processDeaths(int width, int height) {
        int deaths = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (cell.occupant() instanceof Particle p && !p.isAlive()) {
                    botRegistry.unregisterByEntity(p.id());
                    worldGrid.clearEntity(x, y);
                    deaths++;
                } else if (cell.occupant() instanceof Entity.BondedPair bp && !bp.isAlive()) {
                    botRegistry.unregisterByEntity(bp.primaryEntityId());
                    botRegistry.unregisterByEntity(bp.secondaryEntityId());
                    worldGrid.clearEntity(x, y);
                    deaths++;
                }
            }
        }
        return deaths;
    }

    // ── Phase 4: Nutrient spawning ─────────────────────────────────

    private int processNutrientSpawning(int width, int height) {
        if (config.nutrientSpawnProbability() <= 0) return 0;

        int spawned = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (cell.isEmpty() && rng.nextDouble() < config.nutrientSpawnProbability()) {
                    String id = "nutrient-" + nutrientIdCounter.incrementAndGet();
                    worldGrid.setEntity(x, y, Nutrient.spawn(id));
                    spawned++;
                }
            }
        }
        return spawned;
    }
}
