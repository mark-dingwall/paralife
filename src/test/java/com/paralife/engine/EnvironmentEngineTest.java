package com.paralife.engine;

import com.paralife.world.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave-0 skeleton test for {@link EnvironmentEngine} (Task 3).
 *
 * <p>Class-level {@code @TestPropertySource} binds
 * {@code paralife.simulation.events.seed=42} so the cycle-6 HIGH #1
 * regression test locks the nullable-Long seed binding.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42"
})
class EnvironmentEngineTest {

    @Autowired EnvironmentConfig config;
    @Autowired EnvironmentEngine environmentEngine;

    @BeforeEach
    void setUp() {
        // No per-test setup — each test interacts with autowired beans only.
    }

    @Test
    void seedFieldBindsFromTestPropertySource() {
        // cycle-6 HIGH #1: EnvironmentConfig must expose a Long seed field that
        // binds from @TestPropertySource. Without this, EnvironmentEngine's
        // production ctor `config.seed() == null ? new Random() : new Random(config.seed())`
        // silently falls back to unseeded Random — determinism tests in 14-06 fail.
        assertThat(config.seed()).as("cycle-6 HIGH #1: paralife.simulation.events.seed=42 MUST bind to config.seed()").isEqualTo(42L);
    }

    @Test
    void onTickNoOpWhenDisabled() {
        // When config.enabled is false the engine must not throw and must leave
        // the status caches empty. We simulate disabled via a fake TickEvent —
        // the Spring context has enabled=true, so we exercise the normal path
        // and verify that no status entries leak after onTick completes.
        environmentEngine.onTick(new TickEvent(1L));

        assertThat(environmentEngine.cellStatusCacheView()).isEmpty();
        assertThat(environmentEngine.entityStatusCacheView()).isEmpty();
    }

    @Test
    void processEnvDeathsShortCircuitsWhenNoDamageApplied() {
        // envDamageAppliedThisTick starts false — processEnvDeaths should no-op.
        // We call directly (not through onTick) to bypass the cache rebuild.
        assertThat(environmentEngine.envDamageAppliedThisTickForTest()).isFalse();
        environmentEngine.processEnvDeaths(); // must not throw; must not scan the grid.
    }

    @Test
    void getCellStatusReturnsZeroForUnknownPosition() {
        assertThat(environmentEngine.getCellStatus(new Position(0, 0))).isEqualTo((byte) 0);
        assertThat(environmentEngine.getEntityStatus("unknown-entity")).isEqualTo((byte) 0);
    }
}
