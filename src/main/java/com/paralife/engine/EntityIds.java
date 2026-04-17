package com.paralife.engine;

import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;

/**
 * Shared helper for extracting an entity's id regardless of subtype.
 * Consolidates the helper previously duplicated in {@code EnvironmentEngine}
 * and {@code PerceptionBroadcaster} (cycle-3 HIGH).
 *
 * <p>Returns {@code null} for {@link Entity.Rock} and {@link Entity.Nutrient}
 * since those passive entities are never tracked in buff / infection
 * registries keyed by id.
 */
public final class EntityIds {

    private EntityIds() {
        // utility — not instantiable
    }

    /**
     * Returns the id for active entities (Particle, BondedPair, CompositeMember).
     * Returns {@code null} for passive terrain (Rock) or resources (Nutrient).
     *
     * @param e an entity, or {@code null}
     * @return the entity id or {@code null}
     */
    public static String entityIdOf(Entity e) {
        if (e == null) return null;
        return switch (e) {
            case Particle p -> p.id();
            case BondedPair bp -> bp.id();
            case CompositeMember cm -> cm.id();
            case Entity.Rock r -> null;
            case Entity.Nutrient n -> null;
        };
    }
}
