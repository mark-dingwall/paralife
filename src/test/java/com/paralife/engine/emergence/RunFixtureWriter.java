package com.paralife.engine.emergence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dumps a {@link RunResult} to a JSON file in the phase fixtures directory
 * and keeps only the N most recent runs. Schema is D-06b, extended with
 * {@code starvingPreyWindows} + {@code fleeWindows} per REVIEWS MEDIUM so
 * 16-06 trigger-window outcomes are preserved in the narrative trace.
 */
public final class RunFixtureWriter {

    /** Retention window: keep this many most-recent runs and delete older ones. */
    public static final int KEEP = 5;

    private RunFixtureWriter() {}

    public static void dumpAndRollover(Path dir, RunResult result) throws IOException {
        Files.createDirectories(dir);
        String filename = "run-" + Instant.now().toString().replaceAll("[:.]", "-") + ".json";
        Path target = dir.resolve(filename);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), result);

        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> runs = paths
                    .filter(p -> p.getFileName().toString().startsWith("run-")
                              && p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(RunFixtureWriter::lastModifiedMsDesc))
                    .toList();
            for (int i = KEEP; i < runs.size(); i++) {
                Files.deleteIfExists(runs.get(i));
            }
        }
    }

    private static long lastModifiedMsDesc(Path p) {
        try {
            return -Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * RunResult fixture schema (D-06b extended). REVIEWS MEDIUM makes
     * {@code starvingPreyWindows} + {@code fleeWindows} mandatory lists
     * so R17 narrative traceability is always captured.
     */
    public record RunResult(
            long masterSeed,
            String runStarted,
            int tickCount,
            int botCount,
            WorldDim world,
            EmergenceCounts emergence,
            Stability stability,
            List<PopulationSample> populations,
            List<TriggerWindowResult> starvingPreyWindows,
            List<TriggerWindowResult> fleeWindows
    ) {
        public record WorldDim(int width, int height) {}

        public record EmergenceCounts(long bondedPairsFormed, long compositesFormed,
                                      long buffsGranted, long mutagenInfections) {}

        public record Stability(double tickDriftPercent, double tickWorkMsMean, double tickWorkMsP99,
                                int sessionDropouts, double heapGrowthPercent,
                                long errorLogCount, int activeSessionsFinal,
                                String autocorrelationWinningType, int autocorrelationWinningLag,
                                double autocorrelationWinningValue) {}

        public record PopulationSample(long tick, int catalyst, int membrane, int spore) {}

        public record TriggerWindowResult(String triggerEntityId, String triggerType,
                                          long startTick, int sampleCount,
                                          double meanObserverDensity, double baselineDensity,
                                          boolean signalHeld) {}
    }
}
