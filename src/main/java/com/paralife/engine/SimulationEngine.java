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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final CompositeRegistry compositeRegistry;
    private final CompositeConfig compositeConfig;
    private final AtomicLong nutrientIdCounter = new AtomicLong(0);
    private final AtomicInteger lastTickBondCount = new AtomicInteger(0);
    /** Tracks previous tick's pool energy per composite for panic zone decrease detection (D-31). */
    private final ConcurrentHashMap<String, Integer> previousPoolEnergy = new ConcurrentHashMap<>();

    public SimulationEngine(WorldGrid worldGrid, SimulationConfig config,
                            BotRegistry botRegistry, BondingConfig bondingConfig,
                            CompositeRegistry compositeRegistry, CompositeConfig compositeConfig) {
        this.worldGrid = worldGrid;
        this.config = config;
        this.botRegistry = botRegistry;
        this.bondingConfig = bondingConfig;
        this.compositeRegistry = compositeRegistry;
        this.compositeConfig = compositeConfig;
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

        // Phase 1: Interaction resolution (bonding, combat, composite formation)
        int[] interactionCounts = processInteractions(width, height);
        int combatEvents = interactionCounts[0];
        int bondEvents = interactionCounts[1];
        int compositeEvents = interactionCounts[2];
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
            log.debug("Tick {} simulation: combat={}, bonds={}, composites={}, decayed={}, overcrowded={}, deaths={}, nutrients_spawned={}",
                    tickNumber, combatEvents, bondEvents, compositeEvents, decayed, overcrowded, deaths, spawned);
        }
    }

    // ── Phase 1: Interactions (combat + bonding) ──────────────────

    private sealed interface InteractionResult {}
    private record CombatDelta(Position pos, int energyDelta) implements InteractionResult {}
    private record BondFormation(Position primaryPos, Position secondaryPos,
                                  Particle predator, Particle prey) implements InteractionResult {}
    private record CompositeFormation(Position pos1, Position pos2,
                                       Entity.BondedPair bp1, Entity.BondedPair bp2) implements InteractionResult {}

    /**
     * For each particle, check adjacent cells for interactions:
     * - Predator+prey pair eligible for bonding → form BondedPair
     * - Otherwise → standard RPS combat
     * - Particle attacking BondedPair → probabilistic defense
     * - Adjacent BondedPairs → composite formation (D-01)
     *
     * Uses snapshot reads + deferred writes to avoid order-dependent results.
     * Cells are processed in random order to prevent spatial bias.
     *
     * @return int[3]: [combatEvents, bondEvents, compositeEvents]
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

                // Case 3: Particle attacks CompositeMember (D-12)
                // RPS check: attacker must be predator of CompositeMember's type
                if (defender instanceof Entity.CompositeMember cm
                        && attacker.type().prey() == cm.type()) {
                    // DEFENDER role absorption check (reuses bondDefenseChance)
                    if (cm.role() == Entity.Role.DEFENDER
                            && rng.nextDouble() < bondingConfig.bondDefenseChance()) {
                        // Deflected by DEFENDER
                    } else {
                        // Damage hits individual energy (D-12, D-15)
                        results.add(new CombatDelta(pos, config.combatEnergyTransfer()));
                        results.add(new CombatDelta(nPos, -config.combatEnergyTransfer()));
                    }
                }
            }
        }

        // Scan for CompositeMember attackers (D-10, D-11, D-13)
        List<Position> compositeMemberPositions = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (cell.occupant() instanceof Entity.CompositeMember) {
                    compositeMemberPositions.add(new Position(x, y));
                }
            }
        }
        Collections.shuffle(compositeMemberPositions, rng);

        for (Position pos : compositeMemberPositions) {
            Cell cell = worldGrid.getCell(pos.x(), pos.y());
            if (!(cell.occupant() instanceof Entity.CompositeMember attacker)) continue;

            for (Position nPos : worldGrid.getNeighbors(pos.x(), pos.y())) {
                Cell nc = worldGrid.getCell(nPos.x(), nPos.y());
                Entity defender = nc.occupant();
                if (defender == null) continue;

                // Skip same-composite members (D-13)
                if (defender instanceof Entity.CompositeMember cm
                        && cm.compositeId().equals(attacker.compositeId())) continue;

                if (attacker.role() == Entity.Role.ATTACKER) {
                    // True damage — type-agnostic (D-10)
                    if (defender instanceof Particle || defender instanceof Entity.BondedPair
                            || defender instanceof Entity.CompositeMember) {
                        results.add(new CombatDelta(nPos, -config.combatEnergyTransfer()));
                    }
                } else {
                    // Position-based combat: RPS rules based on member's type (D-11)
                    if (defender instanceof Particle prey && attacker.type().prey() == prey.type()) {
                        results.add(new CombatDelta(nPos, -config.combatEnergyTransfer()));
                    } else if (defender instanceof Entity.BondedPair bp
                            && attacker.type().prey() == bp.primaryType()) {
                        results.add(new CombatDelta(nPos, -config.combatEnergyTransfer()));
                    } else if (defender instanceof Entity.CompositeMember cm
                            && attacker.type().prey() == cm.type()) {
                        results.add(new CombatDelta(nPos, -config.combatEnergyTransfer()));
                    }
                }
                break; // Each member attacks at most one neighbor per tick
            }
        }

        // Scan for composite formation (D-01): adjacent BondedPair pairs
        if (compositeConfig.canFormComposites()) {
            List<Position> bondedPairPositions = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    Cell cell = worldGrid.getCell(x, y);
                    if (cell.occupant() instanceof Entity.BondedPair) {
                        bondedPairPositions.add(new Position(x, y));
                    }
                }
            }
            Collections.shuffle(bondedPairPositions, ThreadLocalRandom.current());
            Set<Position> scannedForComposite = new HashSet<>();
            for (Position bpPos : bondedPairPositions) {
                if (scannedForComposite.contains(bpPos)) continue;
                Cell cell = worldGrid.getCell(bpPos.x(), bpPos.y());
                if (!(cell.occupant() instanceof Entity.BondedPair bp1)) continue;
                for (Position nPos : worldGrid.getNeighbors(bpPos.x(), bpPos.y())) {
                    if (scannedForComposite.contains(nPos)) continue;
                    Cell nc = worldGrid.getCell(nPos.x(), nPos.y());
                    if (nc.occupant() instanceof Entity.BondedPair bp2) {
                        results.add(new CompositeFormation(bpPos, nPos, bp1, bp2));
                        scannedForComposite.add(bpPos);
                        scannedForComposite.add(nPos);
                        break; // Each BondedPair tries to merge with first available neighbor
                    }
                }
            }
        }

        // Apply results — combat deltas first, then bond formations, then composite formations
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
                } else if (c.occupant() instanceof Entity.CompositeMember cm) {
                    worldGrid.setEntity(delta.pos.x(), delta.pos.y(),
                            cm.withEnergy(cm.energy() + delta.energyDelta));
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

        // Apply composite formations (D-01: adjacent BondedPairs merge)
        int compositeEvents = 0;
        for (InteractionResult result : results) {
            if (result instanceof CompositeFormation cf) {
                if (claimedForBonding.contains(cf.pos1()) || claimedForBonding.contains(cf.pos2())) continue;
                // Verify cells still hold BondedPairs
                Cell c1 = worldGrid.getCell(cf.pos1().x(), cf.pos1().y());
                Cell c2 = worldGrid.getCell(cf.pos2().x(), cf.pos2().y());
                if (!(c1.occupant() instanceof Entity.BondedPair) || !(c2.occupant() instanceof Entity.BondedPair)) continue;

                String compositeId = "composite-" + UUID.randomUUID().toString().substring(0, 8);
                // Determine surface member (more empty neighbors = surface = FEEDER per D-09)
                int emptyNeighbors1 = countEmptyNeighbors(cf.pos1());
                int emptyNeighbors2 = countEmptyNeighbors(cf.pos2());
                Entity.Role role1 = emptyNeighbors1 >= emptyNeighbors2 ? Entity.Role.FEEDER : Entity.Role.LOCOMOTOR;
                Entity.Role role2 = role1 == Entity.Role.FEEDER ? Entity.Role.LOCOMOTOR : Entity.Role.FEEDER;

                // Create CompositeMember entities — individual energy = half of source BondedPair energy
                String memberId1 = "cm-" + UUID.randomUUID().toString().substring(0, 8);
                String memberId2 = "cm-" + UUID.randomUUID().toString().substring(0, 8);
                var member1 = new Entity.CompositeMember(memberId1, compositeId, cf.bp1().primaryType(), role1,
                        cf.bp1().energy() / 2, cf.bp1().maxEnergy() / 2);
                var member2 = new Entity.CompositeMember(memberId2, compositeId, cf.bp2().primaryType(), role2,
                        cf.bp2().energy() / 2, cf.bp2().maxEnergy() / 2);

                // Place on grid
                worldGrid.setEntity(cf.pos1().x(), cf.pos1().y(), member1);
                worldGrid.setEntity(cf.pos2().x(), cf.pos2().y(), member2);

                // Register in CompositeRegistry — shared pool = sum of both BondedPair energies
                int sharedPool = cf.bp1().energy() + cf.bp2().energy();
                int maxPool = cf.bp1().maxEnergy() + cf.bp2().maxEnergy();
                compositeRegistry.register(compositeId, List.of(memberId1, memberId2),
                        Map.of(memberId1, cf.pos1(), memberId2, cf.pos2()),
                        sharedPool, maxPool);

                // Update BotRegistry for all 4 original entity IDs (2 per BondedPair)
                updateBotRegistryForFormation(cf.bp1(), memberId1, cf.pos1());
                updateBotRegistryForFormation(cf.bp2(), memberId2, cf.pos2());

                claimedForBonding.add(cf.pos1());
                claimedForBonding.add(cf.pos2());
                compositeEvents++;
            }
        }

        return new int[]{combatEvents / 2, bondEvents, compositeEvents};
    }

    private int countEmptyNeighbors(Position pos) {
        int count = 0;
        for (Position nPos : worldGrid.getNeighbors(pos.x(), pos.y())) {
            if (worldGrid.getCell(nPos.x(), nPos.y()).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private void updateBotRegistryForFormation(Entity.BondedPair bp, String newMemberId, Position pos) {
        // Map primary entity's session to new member
        botRegistry.getSessionForEntity(bp.primaryEntityId()).ifPresent(sessionId -> {
            botRegistry.unregisterByEntity(bp.primaryEntityId());
            botRegistry.register(sessionId, newMemberId, pos);
        });
        // Map secondary entity's session to new member
        botRegistry.getSessionForEntity(bp.secondaryEntityId()).ifPresent(sessionId -> {
            botRegistry.unregisterByEntity(bp.secondaryEntityId());
            botRegistry.register(sessionId, newMemberId, pos);
        });
    }

    // ── Phase 2: Energy decay ──────────────────────────────────────
    // CompositeMember energy handled by CompositeEnergyDistributor (Plan 12-02)

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
    // CompositeMember overcrowding handled by CompositeEnergyDistributor (Plan 12-02)

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

        // Phase 3a: Particle and BondedPair death
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

        // Phase 3b: CompositeMember death — dissolution/degradation (D-29)
        Set<String> processedComposites = new HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (cell.occupant() instanceof Entity.CompositeMember cm && !cm.isAlive()) {
                    handleMemberDeath(cm, new Position(x, y), processedComposites);
                    deaths++;
                }
            }
        }

        // Phase 3c: Panic zone check for all composites (D-31)
        // Snapshot current pool energies for decrease detection
        Map<String, Integer> currentPoolEnergies = new HashMap<>();
        for (var composite : compositeRegistry.getAll()) {
            if (processedComposites.contains(composite.getCompositeId())) continue;
            currentPoolEnergies.put(composite.getCompositeId(), composite.getSharedPoolEnergy());
        }

        for (var composite : new ArrayList<>(compositeRegistry.getAll())) {
            if (processedComposites.contains(composite.getCompositeId())) continue;
            checkPanicZone(composite, processedComposites);
        }

        // Update previous pool energy tracking for next tick
        previousPoolEnergy.clear();
        previousPoolEnergy.putAll(currentPoolEnergies);
        // Also track composites that weren't processed (survived this tick)
        for (var composite : compositeRegistry.getAll()) {
            if (!previousPoolEnergy.containsKey(composite.getCompositeId())) {
                previousPoolEnergy.put(composite.getCompositeId(), composite.getSharedPoolEnergy());
            }
        }

        return deaths;
    }

    /**
     * Handle death of a CompositeMember (D-29).
     * 97% graceful degradation (composite shrinks), 3% full dissolution (shatter).
     */
    private void handleMemberDeath(Entity.CompositeMember deadMember, Position deadPos,
                                    Set<String> processedComposites) {
        String compositeId = deadMember.compositeId();
        var compositeOpt = compositeRegistry.getComposite(compositeId);
        if (compositeOpt.isEmpty() || processedComposites.contains(compositeId)) {
            worldGrid.clearEntity(deadPos.x(), deadPos.y());
            botRegistry.unregisterByEntity(deadMember.id());
            return;
        }
        var composite = compositeOpt.get();

        // Remove dead member from grid and registries
        worldGrid.clearEntity(deadPos.x(), deadPos.y());
        botRegistry.unregisterByEntity(deadMember.id());
        compositeRegistry.removeMember(compositeId, deadMember.id());

        int remainingCount = composite.getMemberCount();

        if (remainingCount == 0) {
            compositeRegistry.dissolve(compositeId);
            processedComposites.add(compositeId);
            return;
        }

        if (remainingCount == 1) {
            // D-30: Revert last member to BondedPair
            revertToBondedPair(composite, processedComposites);
            return;
        }

        // D-29: Roll for graceful degradation vs full dissolution
        if (ThreadLocalRandom.current().nextDouble() < compositeConfig.dissolutionChance()) {
            // Full dissolution — shatter surviving members to Particles
            dissolveToParticles(composite, processedComposites);
        } else {
            // Graceful degradation — composite shrinks, continues
            processedComposites.add(compositeId);
        }
    }

    /**
     * Revert the last remaining member of a composite to a BondedPair (D-30).
     */
    private void revertToBondedPair(CompositeRegistry.CompositeState composite,
                                     Set<String> processedComposites) {
        String memberId = composite.getMemberIds().get(0);
        Position pos = composite.getPositionForMember(memberId);
        if (pos == null) {
            compositeRegistry.dissolve(composite.getCompositeId());
            processedComposites.add(composite.getCompositeId());
            return;
        }

        Cell cell = worldGrid.getCell(pos.x(), pos.y());
        if (cell.occupant() instanceof Entity.CompositeMember cm) {
            var bondedPair = new Entity.BondedPair(
                    "bp-" + cm.id(), cm.type(), cm.type(), cm.energy(), cm.maxEnergy(),
                    cm.id(), cm.id());
            worldGrid.setEntity(pos.x(), pos.y(), bondedPair);

            // Update BotRegistry: remap session from CompositeMember to BondedPair
            botRegistry.getSessionForEntity(cm.id()).ifPresent(sessionId ->
                    botRegistry.remapEntity(sessionId, bondedPair.id(), pos));
        }
        compositeRegistry.dissolve(composite.getCompositeId());
        processedComposites.add(composite.getCompositeId());
    }

    /**
     * Dissolve a composite — surviving members revert to solo Particles (D-29 dissolution path).
     */
    private void dissolveToParticles(CompositeRegistry.CompositeState composite,
                                      Set<String> processedComposites) {
        for (String memberId : new ArrayList<>(composite.getMemberIds())) {
            Position pos = composite.getPositionForMember(memberId);
            if (pos == null) continue;
            Cell cell = worldGrid.getCell(pos.x(), pos.y());
            if (cell.occupant() instanceof Entity.CompositeMember cm) {
                var particle = new Particle(cm.id() + "-p", cm.type(), cm.energy(), cm.maxEnergy());
                worldGrid.setEntity(pos.x(), pos.y(), particle);

                // Remap session from CompositeMember to new Particle
                botRegistry.getSessionForEntity(cm.id()).ifPresent(sessionId ->
                        botRegistry.remapEntity(sessionId, particle.id(), pos));
            }
        }
        compositeRegistry.dissolve(composite.getCompositeId());
        processedComposites.add(composite.getCompositeId());
    }

    /**
     * Check if a composite is in the panic zone (pool < criticalEnergyPercent) (D-31).
     * Pool=0 → total death. Pool decreased since last tick → progressive shatter die roll.
     */
    private void checkPanicZone(CompositeRegistry.CompositeState composite,
                                 Set<String> processedComposites) {
        int pool = composite.getSharedPoolEnergy();
        int maxPool = composite.getMaxPoolEnergy();
        if (maxPool == 0) return;

        // Pool=0 → total death (D-31)
        if (pool == 0) {
            for (String memberId : new ArrayList<>(composite.getMemberIds())) {
                Position pos = composite.getPositionForMember(memberId);
                if (pos != null) {
                    worldGrid.clearEntity(pos.x(), pos.y());
                }
                botRegistry.unregisterByEntity(memberId);
            }
            compositeRegistry.dissolve(composite.getCompositeId());
            processedComposites.add(composite.getCompositeId());
            previousPoolEnergy.remove(composite.getCompositeId());
            return;
        }

        double poolPercent = (double) pool / maxPool * 100;
        if (poolPercent >= compositeConfig.criticalEnergyPercent()) return;

        // Only roll if pool decreased since last tick
        Integer prevPool = previousPoolEnergy.get(composite.getCompositeId());
        if (prevPool == null || pool >= prevPool) return; // No decrease or first tick — no roll

        // Progressive shatter die roll: probability scales from 0 (at criticalPercent) to 0.5 (at 0%)
        double shatterProb = (1.0 - poolPercent / compositeConfig.criticalEnergyPercent()) * 0.5;
        if (ThreadLocalRandom.current().nextDouble() < shatterProb) {
            dissolveToParticles(composite, processedComposites);
        }
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
