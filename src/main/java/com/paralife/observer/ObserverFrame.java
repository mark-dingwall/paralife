package com.paralife.observer;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Observer wire DTOs (JSON via Jackson, full-word camelCase keys, schemaVersion 1).
 * Two frame types: a once-per-connection {@link BootstrapFrame} (static terrain) and
 * a per-tick {@link WorldFrame} (everything dynamic).
 */
public final class ObserverFrame {

    private ObserverFrame() {}

    public record GridDims(int width, int height) {}

    public record RockDto(int x, int y) {}

    /** Static terrain, sent once on connect (never retransmitted). */
    public record BootstrapFrame(String type, int schemaVersion, GridDims grid, List<RockDto> rocks) {}

    /**
     * One dynamic occupant. Nullable fields are omitted from JSON (NON_NULL): a
     * nutrient has only kind/energy; a particle adds species/brained; a bondedPair
     * uses primarySpecies/secondarySpecies; a compositeMember adds compositeId/role.
     * {@code mutated} is true-only: a clean entity omits the key rather than sending false.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntityDto(
            int x, int y, String kind,
            String species, Integer energy, Boolean brained,
            String primarySpecies, String secondarySpecies,
            String compositeId, String role, Boolean mutated) {

        /** True-only wire encoding: false collapses to an omitted key. */
        private static Boolean trueOrNull(boolean flag) {
            return flag ? Boolean.TRUE : null;
        }

        public static EntityDto particle(int x, int y, String species, int energy,
                                         boolean brained, boolean mutated) {
            return new EntityDto(x, y, "particle", species, energy, brained,
                    null, null, null, null, trueOrNull(mutated));
        }

        public static EntityDto nutrient(int x, int y, int level) {
            return new EntityDto(x, y, "nutrient", null, level, null, null, null, null, null, null);
        }

        public static EntityDto bondedPair(int x, int y, String primary, String secondary,
                                           int energy, boolean brained, boolean mutated) {
            return new EntityDto(x, y, "bondedPair", null, energy, brained,
                    primary, secondary, null, null, trueOrNull(mutated));
        }

        public static EntityDto compositeMember(int x, int y, String species, String compositeId,
                                                String role, int energy, boolean brained,
                                                boolean mutated) {
            return new EntityDto(x, y, "compositeMember", species, energy, brained,
                    null, null, compositeId, role, trueOrNull(mutated));
        }
    }

    public record ToxinCell(int x, int y, int intensity) {}

    public record MutagenCell(int x, int y, int strain) {}

    public record Coord(int x, int y) {}

    public record EnvDto(List<ToxinCell> toxin, List<MutagenCell> mutagen, List<Coord> lightning) {}

    /** Per-tick dynamic frame. */
    public record WorldFrame(
            String type, int schemaVersion, long tick,
            List<EntityDto> entities, EnvDto env,
            Map<String, Long> scoreboard, Map<String, Integer> populations) {}
}
