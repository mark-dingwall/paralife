package com.paralife.engine;

import com.paralife.world.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotRegistryTest {

    private BotRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BotRegistry();
    }

    @Test
    void registerAndLookupBySession() {
        registry.register("s1", "e1", new Position(5, 10));

        var bot = registry.getBySession("s1");
        assertThat(bot).isPresent();
        assertThat(bot.get().entityId()).isEqualTo("e1");
        assertThat(bot.get().position()).isEqualTo(new Position(5, 10));
    }

    @Test
    void registerAndLookupByEntity() {
        registry.register("s1", "e1", new Position(5, 10));

        assertThat(registry.getSessionForEntity("e1")).contains("s1");
    }

    @Test
    void unknownSessionReturnsEmpty() {
        assertThat(registry.getBySession("nonexistent")).isEmpty();
    }

    @Test
    void unknownEntityReturnsEmpty() {
        assertThat(registry.getSessionForEntity("nonexistent")).isEmpty();
    }

    @Test
    void updatePosition() {
        registry.register("s1", "e1", new Position(0, 0));
        registry.updatePosition("s1", new Position(3, 4));

        var bot = registry.getBySession("s1");
        assertThat(bot).isPresent();
        assertThat(bot.get().position()).isEqualTo(new Position(3, 4));
    }

    @Test
    void updatePositionForUnknownSessionIsNoOp() {
        registry.updatePosition("nonexistent", new Position(1, 1));
        assertThat(registry.size()).isZero();
    }

    @Test
    void unregisterBySession() {
        registry.register("s1", "e1", new Position(0, 0));
        registry.unregisterBySession("s1");

        assertThat(registry.getBySession("s1")).isEmpty();
        assertThat(registry.getSessionForEntity("e1")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void unregisterByEntity() {
        registry.register("s1", "e1", new Position(0, 0));
        registry.unregisterByEntity("e1");

        assertThat(registry.getBySession("s1")).isEmpty();
        assertThat(registry.getSessionForEntity("e1")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void unregisterUnknownIsNoOp() {
        registry.unregisterBySession("nonexistent");
        registry.unregisterByEntity("nonexistent");
        assertThat(registry.size()).isZero();
    }

    @Test
    void getAllBots() {
        registry.register("s1", "e1", new Position(0, 0));
        registry.register("s2", "e2", new Position(1, 1));

        assertThat(registry.getAllBots()).hasSize(2);
    }

    @Test
    void clear() {
        registry.register("s1", "e1", new Position(0, 0));
        registry.register("s2", "e2", new Position(1, 1));
        registry.clear();

        assertThat(registry.size()).isZero();
        assertThat(registry.getBySession("s1")).isEmpty();
        assertThat(registry.getSessionForEntity("e1")).isEmpty();
    }

    @Test
    void multipleBotsConcurrent() {
        // Register many bots to exercise concurrency paths
        for (int i = 0; i < 100; i++) {
            registry.register("s" + i, "e" + i, new Position(i % 10, i / 10));
        }
        assertThat(registry.size()).isEqualTo(100);
        assertThat(registry.getAllBots()).hasSize(100);

        // Unregister half by session, half by entity
        for (int i = 0; i < 50; i++) {
            registry.unregisterBySession("s" + i);
        }
        for (int i = 50; i < 100; i++) {
            registry.unregisterByEntity("e" + i);
        }
        assertThat(registry.size()).isZero();
    }
}
