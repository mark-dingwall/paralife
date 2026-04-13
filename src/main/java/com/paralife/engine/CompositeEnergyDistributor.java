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
import java.util.concurrent.ThreadLocalRandom;

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

    public CompositeEnergyDistributor(WorldGrid worldGrid,
                                       CompositeRegistry compositeRegistry,
                                       CompositeConfig config) {
        this.worldGrid = worldGrid;
        this.compositeRegistry = compositeRegistry;
        this.config = config;
    }

    @EventListener
    @Order(15) // After SimulationEngine(10), before ActionResolver(20)
    public void onTick(TickEvent event) {
        for (var composite : compositeRegistry.getAll()) {
            processCompositeEnergy(composite);
        }
    }

    private void processCompositeEnergy(CompositeRegistry.CompositeState composite) {
        List<String> memberIds = new ArrayList<>(composite.getMemberIds());
        Collections.shuffle(memberIds, ThreadLocalRandom.current()); // prevent healing starvation

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

            // Phase 1: Decay (drain individual energy)
            int newEnergy = member.energy() - passiveDrain;

            // Phase 2: Healing (draw from shared pool if below max)
            int deficit = member.maxEnergy() - Math.max(newEnergy, 0);
            if (deficit > 0 && composite.getSharedPoolEnergy() > 0) {
                int healAmount = Math.min(passiveDrain, deficit);
                int actualHealed = composite.drainEnergy(healAmount);
                newEnergy = Math.max(newEnergy, 0) + actualHealed;
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
