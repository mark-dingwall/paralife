package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration for the bonding (endosymbiosis) mechanic.
 * Bound from application.yml under "paralife.bonding".
 *
 * <p>Phase 13 (Plan 02) extends this with hybrid vigor (D-05) and bond decay
 * cost reduction (D-06) ranges used at BondedPair formation time.
 *
 * @param bondEnergyThreshold minimum energy both predator and prey must have for bonding eligibility
 * @param bondingProbability  probability (0.0-1.0) that an eligible encounter results in bonding
 * @param bondDefenseChance   probability (0.0-1.0) that a BondedPair deflects an incoming attack
 * @param bondRateBonusMin    lower bound of hybrid vigor random bonus factor (D-05)
 * @param bondRateBonusMax    upper bound of hybrid vigor random bonus factor (D-05)
 * @param bondDecayCostMin    lower bound of bond decay cost factor applied to summed decay (D-06)
 * @param bondDecayCostMax    upper bound of bond decay cost factor applied to summed decay (D-06)
 */
@ConfigurationProperties(prefix = "paralife.bonding")
public record BondingConfig(
        int bondEnergyThreshold,
        double bondingProbability,
        double bondDefenseChance,
        double bondRateBonusMin,
        double bondRateBonusMax,
        double bondDecayCostMin,
        double bondDecayCostMax
) {
    /**
     * Explicit {@code @ConstructorBinding} on the canonical (7-arg) constructor
     * — required because this record also exposes a 3-arg convenience constructor
     * for backward compat, and Spring otherwise can't disambiguate which to bind.
     */
    @ConstructorBinding
    public BondingConfig {
        if (bondEnergyThreshold < 0)
            throw new IllegalArgumentException("bondEnergyThreshold must be >= 0: " + bondEnergyThreshold);
        if (bondingProbability < 0 || bondingProbability > 1)
            throw new IllegalArgumentException("bondingProbability must be in [0,1]: " + bondingProbability);
        if (bondDefenseChance < 0 || bondDefenseChance > 1)
            throw new IllegalArgumentException("bondDefenseChance must be in [0,1]: " + bondDefenseChance);
        if (bondRateBonusMin < 0 || bondRateBonusMin > 1)
            throw new IllegalArgumentException("bondRateBonusMin must be in [0,1]: " + bondRateBonusMin);
        if (bondRateBonusMax < bondRateBonusMin || bondRateBonusMax > 1)
            throw new IllegalArgumentException(
                    "bondRateBonusMax must be in [bondRateBonusMin,1]: " + bondRateBonusMax);
        if (bondDecayCostMin < 0 || bondDecayCostMin > 1)
            throw new IllegalArgumentException("bondDecayCostMin must be in [0,1]: " + bondDecayCostMin);
        if (bondDecayCostMax < bondDecayCostMin || bondDecayCostMax > 1)
            throw new IllegalArgumentException(
                    "bondDecayCostMax must be in [bondDecayCostMin,1]: " + bondDecayCostMax);
    }

    /**
     * 3-arg convenience constructor used by pre-Phase-13 tests. Fills in the
     * new hybrid vigor / decay cost fields with production defaults
     * (rate bonus 0.1-0.5, decay cost 0.6-0.9).
     */
    public BondingConfig(int bondEnergyThreshold, double bondingProbability, double bondDefenseChance) {
        this(bondEnergyThreshold, bondingProbability, bondDefenseChance, 0.1, 0.5, 0.6, 0.9);
    }

    public static BondingConfig defaults() {
        return new BondingConfig(50, 0.1, 0.25, 0.1, 0.5, 0.6, 0.9);
    }
}
