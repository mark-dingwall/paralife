package com.paralife.engine;

import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LiveEntityRegistry} — SCALE-07 sparse-set registry.
 * Constructed directly: {@code new LiveEntityRegistry(new GridConfig(8, 8))}.
 */
class LiveEntityRegistryTest {

    private LiveEntityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new LiveEntityRegistry(new GridConfig(8, 8));
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    void registerAddsEntry() {
        registry.register("e-1", new Position(3, 4), Optional.empty());
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void registerWithSessionStoresSession() {
        registry.register("e-1", new Position(3, 4), Optional.of("sess-A"));
        List<LiveEntityRegistry.EntityEntry> snap = registry.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).sessionId()).contains("sess-A");
    }

    @Test
    void registerIsIdempotentOnSameInputs() {
        registry.register("e-1", new Position(3, 4), Optional.empty());
        registry.register("e-1", new Position(3, 4), Optional.empty()); // second identical call
        assertThat(registry.size()).isEqualTo(1);
    }

    /**
     * REVIEWS MEDIUM-3: conflicting re-register must throw IllegalStateException.
     * Size stays 1 and original entry preserved.
     */
    @Test
    void registerThrowsOnConflictingSessionId() {
        registry.register("e-1", new Position(3, 4), Optional.of("sess-A"));
        assertThatThrownBy(() ->
            registry.register("e-1", new Position(3, 4), Optional.of("sess-B"))
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Conflicting re-register");
        // size stays 1 and original entry preserved
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.snapshot().get(0).sessionId()).contains("sess-A");
    }

    // ── unregister ────────────────────────────────────────────────────────────

    @Test
    void unregisterRemovesEntry() {
        registry.register("e-1", new Position(3, 4), Optional.empty());
        registry.unregister("e-1");
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void unregisterIsIdempotent() {
        registry.register("e-1", new Position(3, 4), Optional.empty());
        registry.unregister("e-1");
        registry.unregister("e-1"); // second call — no throw
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void unregisterIsO1AndDoesNotShift() {
        // Register 5 entities, remove the middle one, confirm remaining 4 are intact.
        for (int i = 0; i < 5; i++) {
            registry.register("e-" + i, new Position(i, 0), Optional.empty());
        }
        registry.unregister("e-2");
        assertThat(registry.size()).isEqualTo(4);
        List<LiveEntityRegistry.EntityEntry> remaining = registry.snapshot();
        assertThat(remaining).extracting(LiveEntityRegistry.EntityEntry::entityId)
                .containsExactlyInAnyOrder("e-0", "e-1", "e-3", "e-4");
    }

    // ── snapshot ─────────────────────────────────────────────────────────────

    @Test
    void snapshotIsShallowCopy() {
        registry.register("e-1", new Position(1, 2), Optional.empty());
        List<LiveEntityRegistry.EntityEntry> snap1 = registry.snapshot();
        registry.register("e-2", new Position(2, 3), Optional.empty());
        List<LiveEntityRegistry.EntityEntry> snap2 = registry.snapshot();
        assertThat(snap1).hasSize(1);
        assertThat(snap2).hasSize(2);
    }

    /**
     * REVIEWS HIGH-1: snapshot must be sorted by ROW-MAJOR linear index
     * {@code x * height + y} (height = 8 here).
     *
     * Positions (2,1), (1,5), (1,2), (0,7) → indices 17, 13, 10, 7
     * → sorted order: (0,7), (1,2), (1,5), (2,1)
     */
    @Test
    void snapshotIsSortedByRowMajor() {
        registry.register("a", new Position(2, 1), Optional.empty()); // index 17
        registry.register("b", new Position(1, 5), Optional.empty()); // index 13
        registry.register("c", new Position(1, 2), Optional.empty()); // index 10
        registry.register("d", new Position(0, 7), Optional.empty()); // index 7
        List<LiveEntityRegistry.EntityEntry> snap = registry.snapshot();
        assertThat(snap).extracting(e -> e.position())
                .containsExactly(
                        new Position(0, 7),
                        new Position(1, 2),
                        new Position(1, 5),
                        new Position(2, 1)
                );
    }

    /**
     * REVIEWS HIGH-1: row-major sort must hold even after removals + re-insertions.
     */
    @Test
    void snapshotIsSortedByRowMajorAfterRemovals() {
        // Register 4, remove middle 2, register 2 new.
        registry.register("a", new Position(3, 3), Optional.empty()); // index 27
        registry.register("b", new Position(1, 1), Optional.empty()); // index 9
        registry.register("c", new Position(2, 5), Optional.empty()); // index 21
        registry.register("d", new Position(0, 6), Optional.empty()); // index 6
        registry.unregister("b"); // index 9
        registry.unregister("c"); // index 21
        registry.register("e", new Position(0, 2), Optional.empty()); // index 2
        registry.register("f", new Position(4, 0), Optional.empty()); // index 32

        List<LiveEntityRegistry.EntityEntry> snap = registry.snapshot();
        assertThat(snap).extracting(e -> e.position())
                .containsExactly(
                        new Position(0, 2),  // index 2
                        new Position(0, 6),  // index 6
                        new Position(3, 3),  // index 27
                        new Position(4, 0)   // index 32
                );
    }

    // ── updatePosition ────────────────────────────────────────────────────────

    @Test
    void updatePositionMutatesEntry() {
        registry.register("e-1", new Position(1, 1), Optional.empty());
        registry.updatePosition("e-1", new Position(5, 5));
        assertThat(registry.snapshot().get(0).position()).isEqualTo(new Position(5, 5));
    }

    @Test
    void updatePositionPreservesSessionId() {
        registry.register("e-1", new Position(1, 1), Optional.of("sess-X"));
        registry.updatePosition("e-1", new Position(5, 5));
        assertThat(registry.snapshot().get(0).sessionId()).contains("sess-X");
    }

    @Test
    void updatePositionMissingIsNoop() {
        // Should not throw for unknown entityId
        registry.updatePosition("nonexistent", new Position(0, 0));
        assertThat(registry.size()).isEqualTo(0);
    }

    // ── concurrency ───────────────────────────────────────────────────────────

    @Test
    void concurrentRegisterIsSafe() throws InterruptedException {
        int threadsCount = 4;
        int idsPerThread = 100;
        CountDownLatch ready = new CountDownLatch(threadsCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadsCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadsCount);
        for (int t = 0; t < threadsCount; t++) {
            int base = t * idsPerThread;
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    for (int i = 0; i < idsPerThread; i++) {
                        registry.register("e-" + (base + i),
                                new Position((base + i) % 8, (base + i) % 8),
                                Optional.empty());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(registry.size()).isEqualTo(threadsCount * idsPerThread);

        // Snapshot must still be row-major sorted.
        List<LiveEntityRegistry.EntityEntry> snap = registry.snapshot();
        for (int i = 1; i < snap.size(); i++) {
            int prevIdx = snap.get(i - 1).position().x() * 8 + snap.get(i - 1).position().y();
            int currIdx = snap.get(i).position().x() * 8 + snap.get(i).position().y();
            assertThat(currIdx).isGreaterThanOrEqualTo(prevIdx);
        }
    }
}
