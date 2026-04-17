package com.paralife.engine;

import com.paralife.engine.BuffRegistry.ActiveBuff;
import com.paralife.engine.BuffRegistry.BuffType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain JUnit 5 unit tests for {@link BuffRegistry}. No Spring context —
 * BuffRegistry has no Spring collaborators beyond {@code @Component} scoping.
 *
 * <p>Covers the complete lifecycle per Task 1 acceptance criteria:
 * grant, getBuffs, hasBuff, expireBuffs, unregisterEntity, clear, dedup,
 * dedup-preserves-later-expiry.
 */
class BuffRegistryTest {

    private BuffRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BuffRegistry();
    }

    @Test
    void grantAddsBuffToEmptyEntity() {
        registry.grant("e1", BuffType.ATTACK_PLUS_1, 100L);

        List<ActiveBuff> buffs = registry.getBuffs("e1");
        assertThat(buffs).hasSize(1);
        assertThat(buffs.get(0).type()).isEqualTo(BuffType.ATTACK_PLUS_1);
        assertThat(buffs.get(0).expiryTick()).isEqualTo(100L);
    }

    @Test
    void getBuffsReturnsEmptyListForUnknownEntity() {
        assertThat(registry.getBuffs("nope")).isEmpty();
    }

    @Test
    void hasBuffReturnsTrueAfterGrant() {
        registry.grant("e1", BuffType.MOVEMENT_PLUS_1, 50L);

        assertThat(registry.hasBuff("e1", BuffType.MOVEMENT_PLUS_1)).isTrue();
        assertThat(registry.hasBuff("e1", BuffType.ATTACK_PLUS_1)).isFalse();
        assertThat(registry.hasBuff("other", BuffType.MOVEMENT_PLUS_1)).isFalse();
    }

    @Test
    void expireBuffsRemovesAtOrBeforeExpiryTick() {
        registry.grant("e1", BuffType.SENSOR_PLUS_1, 10L);
        registry.grant("e1", BuffType.UPKEEP_MINUS_1, 20L);

        registry.expireBuffs(10L);

        // SENSOR_PLUS_1 had expiryTick == currentTick → removed.
        assertThat(registry.hasBuff("e1", BuffType.SENSOR_PLUS_1)).isFalse();
        assertThat(registry.hasBuff("e1", BuffType.UPKEEP_MINUS_1)).isTrue();
    }

    @Test
    void expireBuffsDropsEntityWhenAllBuffsExpire() {
        registry.grant("e1", BuffType.ATTACK_PLUS_1, 5L);

        registry.expireBuffs(10L);

        assertThat(registry.size()).isZero();
        assertThat(registry.getBuffs("e1")).isEmpty();
    }

    @Test
    void unregisterEntityRemovesAllBuffs() {
        registry.grant("e1", BuffType.ATTACK_PLUS_1, 100L);
        registry.grant("e1", BuffType.MOVEMENT_PLUS_1, 100L);
        registry.grant("e2", BuffType.ATTACK_PLUS_1, 100L);

        registry.unregisterEntity("e1");

        assertThat(registry.getBuffs("e1")).isEmpty();
        assertThat(registry.getBuffs("e2")).hasSize(1);
    }

    @Test
    void clearRemovesAllEntities() {
        registry.grant("e1", BuffType.ATTACK_PLUS_1, 100L);
        registry.grant("e2", BuffType.MOVEMENT_PLUS_1, 100L);

        registry.clear();

        assertThat(registry.size()).isZero();
        assertThat(registry.getBuffs("e1")).isEmpty();
        assertThat(registry.getBuffs("e2")).isEmpty();
    }

    @Test
    void grantDedupsSameBuffType() {
        // Truth #2: grant() dedupes same BuffType per entity (no numerical stacking).
        registry.grant("e1", BuffType.ATTACK_PLUS_1, 100L);
        registry.grant("e1", BuffType.ATTACK_PLUS_1, 200L);

        List<ActiveBuff> buffs = registry.getBuffs("e1");
        assertThat(buffs).as("same BuffType must NOT produce two entries").hasSize(1);
        assertThat(buffs.get(0).type()).isEqualTo(BuffType.ATTACK_PLUS_1);
        assertThat(buffs.get(0).expiryTick()).isEqualTo(200L);
    }

    @Test
    void grantDedupPreservesLaterExpiry() {
        // Shorter re-grant must NOT shrink an active longer buff — expiryTick
        // becomes max(existing, new).
        registry.grant("e1", BuffType.UPKEEP_MINUS_1, 500L);
        registry.grant("e1", BuffType.UPKEEP_MINUS_1, 100L);

        List<ActiveBuff> buffs = registry.getBuffs("e1");
        assertThat(buffs).hasSize(1);
        assertThat(buffs.get(0).expiryTick())
                .as("shorter re-grant must not shrink active longer buff")
                .isEqualTo(500L);
    }
}
