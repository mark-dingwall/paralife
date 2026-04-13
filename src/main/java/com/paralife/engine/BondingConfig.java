package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the bonding (endosymbiosis) mechanic.
 * Bound from application.yml under "paralife.bonding".
 *
 * @param bondEnergyThreshold minimum energy both predator and prey must have for bonding eligibility
 * @param bondingProbability  probability (0.0-1.0) that an eligible encounter results in bonding
 * @param bondDefenseChance   probability (0.0-1.0) that a BondedPair deflects an incoming attack
 */
@ConfigurationProperties(prefix = "paralife.bonding")
public record BondingConfig(
        int bondEnergyThreshold,
        double bondingProbability,
        double bondDefenseChance
) {
    public BondingConfig {
        if (bondEnergyThreshold < 0)
            throw new IllegalArgumentException("bondEnergyThreshold must be >= 0: " + bondEnergyThreshold);
        if (bondingProbability < 0 || bondingProbability > 1)
            throw new IllegalArgumentException("bondingProbability must be in [0,1]: " + bondingProbability);
        if (bondDefenseChance < 0 || bondDefenseChance > 1)
            throw new IllegalArgumentException("bondDefenseChance must be in [0,1]: " + bondDefenseChance);
    }

    public static BondingConfig defaults() {
        return new BondingConfig(50, 0.1, 0.25);
    }
}
