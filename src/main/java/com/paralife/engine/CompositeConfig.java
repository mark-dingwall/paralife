package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the composite entity system.
 * Bound from application.yml under "paralife.composite".
 *
 * <p>Role drain rates follow D-17: active/passive pairs per role.
 * SENSOR and DEFENDER are passive-only roles.
 *
 * @param dissolutionChance       probability (0.0-1.0) of full shatter on member death (D-29)
 * @param criticalEnergyPercent   pool % threshold below which shatter die rolls begin (D-31)
 * @param speedConstant           multiplier for locomotor_count/colony_size speed formula (D-23)
 * @param locomotorPassiveDrain   energy drained per tick when LOCOMOTOR is idle
 * @param locomotorActiveDrain    energy drained per tick when LOCOMOTOR moves composite
 * @param feederPassiveDrain      energy drained per tick when FEEDER is idle
 * @param feederActiveDrain       energy drained per tick when FEEDER consumes
 * @param attackerPassiveDrain    energy drained per tick when ATTACKER is idle
 * @param attackerActiveDrain     energy drained per tick when ATTACKER attacks
 * @param defenderPassiveDrain    energy drained per tick (passive only, D-17)
 * @param reproducerPassiveDrain  energy drained per tick when REPRODUCER is idle
 * @param reproducerActiveDrain   energy drained per tick when REPRODUCER buds
 * @param sensorPassiveDrain      energy drained per tick (passive only, always sensing, D-17)
 * @param canFormComposites       global toggle for composite formation (D-02)
 */
@ConfigurationProperties(prefix = "paralife.composite")
public record CompositeConfig(
        double dissolutionChance,
        int criticalEnergyPercent,
        double speedConstant,
        int locomotorPassiveDrain,
        int locomotorActiveDrain,
        int feederPassiveDrain,
        int feederActiveDrain,
        int attackerPassiveDrain,
        int attackerActiveDrain,
        int defenderPassiveDrain,
        int reproducerPassiveDrain,
        int reproducerActiveDrain,
        int sensorPassiveDrain,
        boolean canFormComposites
) {
    public CompositeConfig {
        if (dissolutionChance < 0 || dissolutionChance > 1)
            throw new IllegalArgumentException("dissolutionChance must be in [0,1]: " + dissolutionChance);
        if (criticalEnergyPercent < 0 || criticalEnergyPercent > 100)
            throw new IllegalArgumentException("criticalEnergyPercent must be in [0,100]: " + criticalEnergyPercent);
        if (speedConstant <= 0)
            throw new IllegalArgumentException("speedConstant must be > 0: " + speedConstant);
        if (locomotorPassiveDrain < 0)
            throw new IllegalArgumentException("locomotorPassiveDrain must be >= 0: " + locomotorPassiveDrain);
        if (locomotorActiveDrain < 0)
            throw new IllegalArgumentException("locomotorActiveDrain must be >= 0: " + locomotorActiveDrain);
        if (feederPassiveDrain < 0)
            throw new IllegalArgumentException("feederPassiveDrain must be >= 0: " + feederPassiveDrain);
        if (feederActiveDrain < 0)
            throw new IllegalArgumentException("feederActiveDrain must be >= 0: " + feederActiveDrain);
        if (attackerPassiveDrain < 0)
            throw new IllegalArgumentException("attackerPassiveDrain must be >= 0: " + attackerPassiveDrain);
        if (attackerActiveDrain < 0)
            throw new IllegalArgumentException("attackerActiveDrain must be >= 0: " + attackerActiveDrain);
        if (defenderPassiveDrain < 0)
            throw new IllegalArgumentException("defenderPassiveDrain must be >= 0: " + defenderPassiveDrain);
        if (reproducerPassiveDrain < 0)
            throw new IllegalArgumentException("reproducerPassiveDrain must be >= 0: " + reproducerPassiveDrain);
        if (reproducerActiveDrain < 0)
            throw new IllegalArgumentException("reproducerActiveDrain must be >= 0: " + reproducerActiveDrain);
        if (sensorPassiveDrain < 0)
            throw new IllegalArgumentException("sensorPassiveDrain must be >= 0: " + sensorPassiveDrain);
    }

    public static CompositeConfig defaults() {
        return new CompositeConfig(0.03, 12, 1.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true);
    }
}
