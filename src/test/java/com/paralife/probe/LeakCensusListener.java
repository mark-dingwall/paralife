package com.paralife.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Opt-in end-of-suite platform-thread census for the Phase 22.1 cache-cap experiment.
 *
 * <p>Registered globally via {@code META-INF/services} but <strong>inert unless</strong> the
 * system property {@code paralife.leakprobe} is set — so the pinned {@code forkEvery=1}
 * {@code test} task is unaffected. Only the dedicated {@code leakProbe} Gradle task enables it.
 *
 * <p>Captured at {@link #testPlanExecutionFinished} — i.e. while Spring's
 * {@code TestContext} cache still holds its contexts (Spring closes them at JVM shutdown).
 * The count is therefore the <em>concurrent cached-context high-water mark</em>, matching
 * the 2026-06-09 deep-dive's {@code forkEvery=0} census (268 threads, uncapped cache).
 *
 * <p>Caveat (unchanged from the prior probe): {@link Thread#getAllStackTraces()} does
 * <strong>not</strong> enumerate virtual threads, so this counts <em>platform</em> threads only —
 * which is exactly the Jetty server/connector ({@code WebSocket@}, {@code qtp}) and client
 * ({@code HttpClient@…-scheduler}) pools the experiment is about.
 */
public class LeakCensusListener implements TestExecutionListener {

    private static final String ENABLE_PROP = "paralife.leakprobe";
    private static final String LABEL_PROP = "paralife.leakprobe.label";
    private static final String CACHE_PROP = "spring.test.context.cache.maxSize";

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (System.getProperty(ENABLE_PROP) == null) {
            return; // inert during normal builds
        }
        String label = System.getProperty(LABEL_PROP, "run");
        String cacheSize = System.getProperty(CACHE_PROP, "<default 32>");

        Set<Thread> threads = Thread.getAllStackTraces().keySet();

        // Group by a name with volatile id/hex/digit runs normalised away, so
        // "qtp1234-56", "HttpClient@1a2b3c-scheduler" collapse into stable buckets.
        TreeMap<String, Integer> buckets = new TreeMap<>();
        for (Thread t : threads) {
            String key = normalise(t.getName());
            buckets.merge(key, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Leak-probe platform-thread census\n");
        sb.append("label=").append(label).append('\n');
        sb.append("spring.test.context.cache.maxSize=").append(cacheSize).append('\n');
        sb.append("capturedAt=").append(Instant.now()).append('\n');
        sb.append("total live platform threads: ").append(threads.size()).append('\n');
        sb.append("# (virtual threads are NOT counted — getAllStackTraces omits them)\n\n");
        sb.append(String.format("%6s  %s%n", "COUNT", "BUCKET"));
        buckets.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sb.append(String.format("%6d  %s%n", e.getValue(), e.getKey())));

        String body = sb.toString();
        // Always echo to stdout so the result survives even if the file write is lost
        // (e.g. container reclaim) — the Gradle test-output captures it.
        System.out.println("\n==== LEAK-PROBE CENSUS [" + label + "] ====\n" + body
                + "==== END LEAK-PROBE CENSUS ====\n");

        try {
            Path dir = Path.of("build", "leak-probe");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("census-" + label + ".txt"), body, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.err.println("[leak-probe] failed to write census file: " + ex);
        }
    }

    private static String normalise(String name) {
        if (name == null) {
            return "<null>";
        }
        return name
                .replaceAll("@[0-9a-fA-F]+", "@HASH")
                .replaceAll("\\d+", "#");
    }
}
