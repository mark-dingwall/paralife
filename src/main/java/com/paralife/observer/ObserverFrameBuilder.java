package com.paralife.observer;

import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.WorldGrid.GridSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure, stateless builder for observer frames. All inputs are immutable snapshots
 * captured on the tick thread; this does no I/O and holds no state, so it is fully
 * unit-testable and safe to call from the broadcaster's {@code @Order} listener.
 *
 * <p>Census rule (H3/H5, matches PopulationHistory): particle → +1 species;
 * bondedPair → +1 primary AND +1 secondary; compositeMember → +1 species (no
 * liveness filter — a zero-energy member awaiting next-tick cleanup still counts);
 * rock/nutrient excluded.
 */
@Component
public class ObserverFrameBuilder {

    public static final int SCHEMA_VERSION = 1;

    public ObserverFrame.BootstrapFrame buildBootstrap(GridSnapshot grid) {
        List<ObserverFrame.RockDto> rocks = new ArrayList<>();
        Cell[][] cells = grid.cells();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                if (cells[x][y].occupant() instanceof Entity.Rock) {
                    rocks.add(new ObserverFrame.RockDto(x, y));
                }
            }
        }
        return new ObserverFrame.BootstrapFrame(
                "bootstrap", SCHEMA_VERSION,
                new ObserverFrame.GridDims(grid.width(), grid.height()),
                List.copyOf(rocks));
    }

    public ObserverFrame.WorldFrame buildWorld(long tick, GridSnapshot grid,
                                               EnvironmentSnapshot env, Set<String> ownedIds,
                                               long[] spawnsByOrdinal) {
        List<ObserverFrame.EntityDto> entities = new ArrayList<>();
        Map<String, Integer> populations = new LinkedHashMap<>();
        for (ParticleType t : ParticleType.values()) {
            populations.put(t.name(), 0);
        }
        Set<String> infectedIds = env.infectedIds();
        Cell[][] cells = grid.cells();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                Entity e = cells[x][y].occupant();
                if (e == null) continue;
                switch (e) {
                    case Entity.Particle p -> {
                        entities.add(ObserverFrame.EntityDto.particle(
                                x, y, p.type().name(), p.energy(), ownedIds.contains(p.id()),
                                infectedIds.contains(p.id())));
                        populations.merge(p.type().name(), 1, Integer::sum);
                    }
                    case Entity.Nutrient n -> entities.add(ObserverFrame.EntityDto.nutrient(x, y, n.level()));
                    case Entity.BondedPair bp -> {
                        entities.add(ObserverFrame.EntityDto.bondedPair(
                                x, y, bp.primaryType().name(), bp.secondaryType().name(),
                                bp.energy(), ownedIds.contains(bp.id()),
                                infectedIds.contains(bp.id())));
                        populations.merge(bp.primaryType().name(), 1, Integer::sum);
                        populations.merge(bp.secondaryType().name(), 1, Integer::sum);
                    }
                    case Entity.CompositeMember cm -> {
                        entities.add(ObserverFrame.EntityDto.compositeMember(
                                x, y, cm.type().name(), cm.compositeId(), cm.role().name(),
                                cm.energy(), ownedIds.contains(cm.id()),
                                infectedIds.contains(cm.id())));
                        populations.merge(cm.type().name(), 1, Integer::sum);
                    }
                    case Entity.Rock ignored -> {
                        // static terrain — excluded from the world frame (bootstrap only)
                    }
                }
            }
        }

        ObserverFrame.EnvDto envDto = new ObserverFrame.EnvDto(
                env.toxin().stream()
                        .map(c -> new ObserverFrame.ToxinCell(c.x(), c.y(), c.value())).toList(),
                env.mutagen().stream()
                        .map(c -> new ObserverFrame.MutagenCell(c.x(), c.y(), c.value())).toList(),
                env.lightning().stream()
                        .map(s -> new ObserverFrame.Strike(s.x(), s.y(), s.radius())).toList());

        Map<String, Long> scoreboard = new LinkedHashMap<>();
        for (ParticleType t : ParticleType.values()) {
            long v = t.ordinal() < spawnsByOrdinal.length ? spawnsByOrdinal[t.ordinal()] : 0L;
            scoreboard.put(t.name(), v);
        }

        return new ObserverFrame.WorldFrame(
                "world", SCHEMA_VERSION, tick, entities, envDto, scoreboard, populations);
    }
}
