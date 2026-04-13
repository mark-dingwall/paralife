package com.paralife.engine;

import com.paralife.world.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeRegistryTest {

    private CompositeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CompositeRegistry();
    }

    // ── Register / Get ────────────────────────────────────────────

    @Test
    void registerCreatesRetrievableEntry() {
        registry.register("comp-1", List.of("m1", "m2"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0)),
                100, 200);
        var composite = registry.getComposite("comp-1");
        assertThat(composite).isPresent();
        assertThat(composite.get().getCompositeId()).isEqualTo("comp-1");
        assertThat(composite.get().getMemberIds()).containsExactlyInAnyOrder("m1", "m2");
        assertThat(composite.get().getSharedPoolEnergy()).isEqualTo(100);
        assertThat(composite.get().getMaxPoolEnergy()).isEqualTo(200);
    }

    @Test
    void getCompositeReturnsEmptyForUnknown() {
        assertThat(registry.getComposite("nonexistent")).isEmpty();
    }

    // ── Member reverse lookup ─────────────────────────────────────

    @Test
    void getCompositeForMemberReturnsCompositeId() {
        registry.register("comp-1", List.of("m1", "m2"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0)),
                100, 200);
        assertThat(registry.getCompositeForMember("m1")).isPresent().contains("comp-1");
        assertThat(registry.getCompositeForMember("m2")).isPresent().contains("comp-1");
    }

    @Test
    void getCompositeForMemberReturnsEmptyForUnknown() {
        assertThat(registry.getCompositeForMember("unknown")).isEmpty();
    }

    // ── Position tracking ─────────────────────────────────────────

    @Test
    void getPositionForMemberReturnsCorrectPosition() {
        registry.register("comp-1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 10), "m2", new Position(6, 10)),
                100, 200);
        assertThat(registry.getPositionForMember("m1")).isEqualTo(new Position(5, 10));
        assertThat(registry.getPositionForMember("m2")).isEqualTo(new Position(6, 10));
    }

    @Test
    void getPositionForMemberReturnsNullForUnknown() {
        assertThat(registry.getPositionForMember("unknown")).isNull();
    }

    @Test
    void updateMemberPositionsUpdatesAllPositions() {
        registry.register("comp-1", List.of("m1", "m2"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0)),
                100, 200);
        registry.updateMemberPositions("comp-1",
                Map.of("m1", new Position(1, 1), "m2", new Position(2, 1)));
        assertThat(registry.getPositionForMember("m1")).isEqualTo(new Position(1, 1));
        assertThat(registry.getPositionForMember("m2")).isEqualTo(new Position(2, 1));
    }

    @Test
    void updateMemberPositionsDoesNotAffectOtherComposites() {
        registry.register("comp-1", List.of("m1"),
                Map.of("m1", new Position(0, 0)),
                50, 100);
        registry.register("comp-2", List.of("m2"),
                Map.of("m2", new Position(5, 5)),
                50, 100);
        registry.updateMemberPositions("comp-1",
                Map.of("m1", new Position(1, 1)));
        assertThat(registry.getPositionForMember("m2")).isEqualTo(new Position(5, 5));
    }

    // ── Remove member ─────────────────────────────────────────────

    @Test
    void removeMemberDecrementsMemberCount() {
        registry.register("comp-1", List.of("m1", "m2", "m3"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0), "m3", new Position(2, 0)),
                100, 200);
        registry.removeMember("comp-1", "m2");
        assertThat(registry.getMemberCount("comp-1")).isEqualTo(2);
    }

    @Test
    void removeMemberRemovesPositionEntry() {
        registry.register("comp-1", List.of("m1", "m2"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0)),
                100, 200);
        registry.removeMember("comp-1", "m2");
        assertThat(registry.getPositionForMember("m2")).isNull();
    }

    @Test
    void removeMemberUpdatesReverseLookup() {
        registry.register("comp-1", List.of("m1", "m2"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0)),
                100, 200);
        registry.removeMember("comp-1", "m2");
        assertThat(registry.getCompositeForMember("m2")).isEmpty();
        assertThat(registry.getCompositeForMember("m1")).isPresent().contains("comp-1");
    }

    // ── Dissolve ──────────────────────────────────────────────────

    @Test
    void dissolveRemovesCompositeAndAllReverseLookups() {
        registry.register("comp-1", List.of("m1", "m2"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0)),
                100, 200);
        registry.dissolve("comp-1");
        assertThat(registry.getComposite("comp-1")).isEmpty();
        assertThat(registry.getCompositeForMember("m1")).isEmpty();
        assertThat(registry.getCompositeForMember("m2")).isEmpty();
    }

    // ── Size / Clear ──────────────────────────────────────────────

    @Test
    void sizeReturnsCorrectCount() {
        assertThat(registry.size()).isEqualTo(0);
        registry.register("comp-1", List.of("m1"),
                Map.of("m1", new Position(0, 0)),
                50, 100);
        assertThat(registry.size()).isEqualTo(1);
        registry.register("comp-2", List.of("m2"),
                Map.of("m2", new Position(1, 0)),
                50, 100);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void clearRemovesAllEntries() {
        registry.register("comp-1", List.of("m1"),
                Map.of("m1", new Position(0, 0)),
                50, 100);
        registry.register("comp-2", List.of("m2"),
                Map.of("m2", new Position(1, 0)),
                50, 100);
        registry.clear();
        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.getCompositeForMember("m1")).isEmpty();
    }

    // ── Energy operations ─────────────────────────────────────────

    @Test
    void addEnergyIncreasesSharedPool() {
        registry.register("comp-1", List.of("m1"),
                Map.of("m1", new Position(0, 0)),
                50, 200);
        registry.addEnergy("comp-1", 30);
        assertThat(registry.getSharedEnergy("comp-1")).isEqualTo(80);
    }

    @Test
    void drainEnergyDecreasesSharedPool() {
        registry.register("comp-1", List.of("m1"),
                Map.of("m1", new Position(0, 0)),
                100, 200);
        int drained = registry.drainEnergy("comp-1", 30);
        assertThat(drained).isEqualTo(30);
        assertThat(registry.getSharedEnergy("comp-1")).isEqualTo(70);
    }

    @Test
    void drainEnergyCannotDrainBelowZero() {
        registry.register("comp-1", List.of("m1"),
                Map.of("m1", new Position(0, 0)),
                50, 200);
        int drained = registry.drainEnergy("comp-1", 100);
        assertThat(drained).isEqualTo(50);
        assertThat(registry.getSharedEnergy("comp-1")).isEqualTo(0);
    }

    // ── Member count ──────────────────────────────────────────────

    @Test
    void getMemberCountReturnsCorrectCountAfterAddAndRemove() {
        registry.register("comp-1", List.of("m1", "m2", "m3"),
                Map.of("m1", new Position(0, 0), "m2", new Position(1, 0), "m3", new Position(2, 0)),
                100, 200);
        assertThat(registry.getMemberCount("comp-1")).isEqualTo(3);
        registry.removeMember("comp-1", "m2");
        assertThat(registry.getMemberCount("comp-1")).isEqualTo(2);
    }

    // ── getAll ────────────────────────────────────────────────────

    @Test
    void getAllReturnsAllComposites() {
        registry.register("comp-1", List.of("m1"),
                Map.of("m1", new Position(0, 0)),
                50, 100);
        registry.register("comp-2", List.of("m2"),
                Map.of("m2", new Position(1, 0)),
                50, 100);
        assertThat(registry.getAll()).hasSize(2);
    }
}
