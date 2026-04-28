package com.paralife.harness;

import com.paralife.bot.RampUpSpec;
import com.paralife.bot.SpeciesMix;

import java.nio.file.Path;

/**
 * Immutable snapshot of the options parsed from the CLI surface (D-15 / D-16).
 * Picocli annotations live on {@link LoadHarness} itself; this record is a
 * value-object carrier for passing resolved options between methods.
 */
public record LoadHarnessOptions(
        String serverUri,
        int count,
        String harnessId,
        RampUpSpec rampUp,
        SpeciesMix speciesMix,
        int durationSeconds,
        Path reportOut,
        String reportMode,
        int reportIntervalSeconds) {}
