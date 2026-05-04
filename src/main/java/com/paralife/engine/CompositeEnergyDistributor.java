package com.paralife.engine;

import com.paralife.world.Entity;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Role;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * Tick pipeline component for composite energy accounting.
 * Runs at @Order(15) — after SimulationEngine(10) combat/death,
 * before ActionResolver(20) bot actions.
 *
 * <p>For each composite:
 * <ol>
 *   <li><b>Passive drain:</b> Each member's individual energy decremented
 *       by their role's passive drain rate (from CompositeConfig)</li>
 *   <li><b>Healing:</b> Members below maxEnergy draw from shared pool
 *       at role-determined rates, shuffled to prevent starvation</li>
 * </ol>
 *
 * <p>Does NOT handle FEEDER income (ActionResolver) or active drain
 * (role-specific action costs in ActionResolver).
 */
@Component
public class CompositeEnergyDistributor {

    private static final Logger log = LoggerFactory.getLogger(CompositeEnergyDistributor.class);

    private final WorldGrid worldGrid;
    private final CompositeRegistry compositeRegistry;
    private final CompositeConfig config;
    /**
     * Plan 14-05: BuffRegistry injected so UPKEEP_MINUS_1 on a composite
     * member reduces that member's per-tick passiveDrain by 1 (floored at 0).
     */
    private BuffRegistry buffRegistry = new BuffRegistry();

    /**
     * Phase 16 Plan 01: seeded RNG derived from {@link CompositeConfig#seed()} via
     * {@link SplittableRandom#split()} — the JDK-standard way to derive an
     * uncorrelated sub-stream without a magic XOR constant (REVIEWS MEDIUM).
     * Non-final so {@link #resetSeed()} can reassign.
     */
    private Random compositeRng;

    public CompositeEnergyDistributor(WorldGrid worldGrid,
                                       CompositeRegistry compositeRegistry,
                                       CompositeConfig config) {
        this.worldGrid = worldGrid;
        this.compositeRegistry = compositeRegistry;
        this.config = config;
        this.compositeRng = buildRng();
    }

    private Random buildRng() {
        if (config.seed() == null) return new Random();
        // SplittableRandom.split() yields an uncorrelated sub-stream; derive a
        // seed for java.util.Random from it. Replaces the XOR-magic pattern
        // flagged in REVIEWS MEDIUM.
        SplittableRandom base = new SplittableRandom(config.seed());
        return new Random(base.split().nextLong());
    }

    /**
     * Phase 16 Plan 01 (REVIEWS HIGH #1): re-initialises {@link #compositeRng}
     * from {@link CompositeConfig#seed()}. Test-only.
     */
    public void resetSeed() {
        this.compositeRng = buildRng();
    }

    /**
     * Plan 14-05: setter-inject {@link BuffRegistry}. {@code required=false}
     * preserves pre-Phase-14 tests that construct this bean directly via the
     * 3-arg ctor (they see the empty-registry default — no buff effects fire).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBuffRegistry(BuffRegistry buffRegistry) {
        if (buffRegistry != null) this.buffRegistry = buffRegistry;
    }

    @EventListener
    @Order(15) // After SimulationEngine(10), before ActionResolver(20)
    public void onTick(TickEvent event) {
        // Phase 19.1 D-05 — stable iteration order before RNG consumption.
        // compositeRegistry.getAll() is backed by ConcurrentHashMap; iterating
        // its entrySet directly into Collections.shuffle(memberIds, compositeRng)
        // produces non-deterministic shuffle inputs across same-seed runs.
        var composites = new java.util.ArrayList<>(compositeRegistry.getAll());
        composites.sort(java.util.Comparator.comparing(CompositeRegistry.CompositeState::getCompositeId));
        for (var composite : composites) {
            processCompositeEnergy(composite);
        }
    }

    private void processCompositeEnergy(CompositeRegistry.CompositeState composite) {
        List<String> memberIds = new ArrayList<>(composite.getMemberIds());
        Collections.shuffle(memberIds, compositeRng); // prevent healing starvation

        for (String memberId : memberIds) {
            Position pos = composite.getPositionForMember(memberId);
            if (pos == null) {
                log.warn("No position found for composite member: {}", memberId);
                continue;
            }

            Entity occupant = worldGrid.getCell(pos.x(), pos.y()).occupant();
            if (!(occupant instanceof CompositeMember member)) {
                log.warn("Expected CompositeMember at {} for member {}, found: {}",
                        pos, memberId, occupant != null ? occupant.getClass().getSimpleName() : "null");
                continue;
            }

            int passiveDrain = getPassiveDrain(member.role());
            // Plan 14-05: UPKEEP_MINUS_1 reduces per-member passiveDrain by 1,
            // floored at 0. Same buff type dedups (BuffRegistry.grant()
            // replaces existing expiry with max) so multiple grants do NOT
            // stack numerically — the reduction is always exactly 1.
            if (buffRegistry.hasBuff(member.id(), BuffRegistry.BuffType.UPKEEP_MINUS_1)) {
                passiveDrain = Math.max(0, passiveDrain - 1);
            }

            // Phase 1: Decay (drain individual energy, clamp to zero)
            int newEnergy = Math.max(member.energy() - passiveDrain, 0);

            // Phase 2: Healing (draw from shared pool if below max)
            int deficit = member.maxEnergy() - newEnergy;
            if (deficit > 0 && composite.getSharedPoolEnergy() > 0) {
                int healAmount = Math.min(passiveDrain, deficit);
                int actualHealed = composite.drainEnergy(healAmount);
                newEnergy = newEnergy + actualHealed;
            }

            // Update entity on grid
            var updated = member.withEnergy(newEnergy);
            worldGrid.setEntity(pos.x(), pos.y(), updated);
        }
    }

    private int getPassiveDrain(Role role) {
        return switch (role) {
            case LOCOMOTOR -> config.locomotorPassiveDrain();
            case FEEDER -> config.feederPassiveDrain();
            case ATTACKER -> config.attackerPassiveDrain();
            case DEFENDER -> config.defenderPassiveDrain();
            case REPRODUCER -> config.reproducerPassiveDrain();
            case SENSOR -> config.sensorPassiveDrain();
        };
    }
}
