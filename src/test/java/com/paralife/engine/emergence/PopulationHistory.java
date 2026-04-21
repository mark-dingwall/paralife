package com.paralife.engine.emergence;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.CompositeRegistry;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Per-tick population history collector used by Phase 16 emergent-behaviour
 * integration tests (16-05 R15 and 16-06 R16/R17/R18).
 *
 * <p>This class is test-only — no Spring annotations, driven manually by
 * sampling loops in tests. Reads authoritative server state via
 * {@link WorldGrid#snapshot()} and sibling registries; never reads wire
 * bitmasks (REVIEWS HIGH #9).
 *
 * <p>Samples four parallel lists at identical index on every call to
 * {@link #sample}: population counts, tick number, active WS session count,
 * heap bytes-used. This gives 16-06 D-11 #4/#5 stability assertions (session
 * dropouts, heap growth) without a second source of truth (BLOCKER 5).
 *
 * <p>Counting rules (per 16-RESEARCH §Population-Observable Surface):
 * <ul>
 *   <li>{@link Entity.Particle} → +1 to its {@code type().name()}</li>
 *   <li>{@link Entity.BondedPair} → +1 to primary AND secondary type</li>
 *   <li>{@link Entity.CompositeMember} → +1 to its {@code type().name()}</li>
 *   <li>{@link Entity.Rock}, {@link Entity.Nutrient} → not counted</li>
 * </ul>
 */
public class PopulationHistory {

    private final List<Map<String, Integer>> history = new ArrayList<>();
    private final List<Long> ticks = new ArrayList<>();
    private final List<Integer> sessionCounts = new ArrayList<>();
    private final List<Long> heapSamples = new ArrayList<>();
    private List<EntitySnapshot> lastEntities = List.of();

    /**
     * Per-tick snapshot of an occupied cell — the unit observed by
     * {@link TriggerWatcher}. {@code id} is sourced from
     * {@link Entity#id()} directly (REVIEWS HIGH #4/#9 — no BotRegistry
     * position lookup) and is never null because the sealed interface
     * mandates a non-null id on every permit.
     */
    public record EntitySnapshot(String id, String type, int flags, Position position, boolean hasBuffs) {}

    /**
     * Full per-tick sample. Populates all four parallel lists at identical
     * index. Should be called once per sampling-loop iteration.
     *
     * @param grid              world grid (snapshot taken under a single read-lock)
     * @param compositeRegistry unused at present — reserved for future composite-pool sampling
     * @param buffRegistry      used to flag per-entity {@code hasBuffs} in the snapshot
     * @param botRegistry       unused at present — reserved for future bot-state audits
     * @param sessionRegistry   active WebSocket session count source (D-11 #4)
     * @param currentTick       authoritative tick number from {@code TickEngine}
     */
    public void sample(WorldGrid grid,
                       CompositeRegistry compositeRegistry,
                       BuffRegistry buffRegistry,
                       BotRegistry botRegistry,
                       SessionRegistry sessionRegistry,
                       long currentTick) {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("CATALYST", 0);
        counts.put("MEMBRANE", 0);
        counts.put("SPORE", 0);
        List<EntitySnapshot> entities = new ArrayList<>();

        WorldGrid.GridSnapshot snap = grid.snapshot();
        for (int x = 0; x < snap.width(); x++) {
            for (int y = 0; y < snap.height(); y++) {
                Cell cell = snap.getCell(x, y);
                Entity e = cell.occupant();
                if (e == null) continue;
                // REVIEWS HIGH #4/#9: Entity.id() is on the sealed interface — use it directly.
                String id = e.id();
                boolean hasBuffs = id != null && !buffRegistry.getBuffs(id).isEmpty();
                switch (e) {
                    case Entity.Particle p -> {
                        counts.merge(p.type().name(), 1, Integer::sum);
                        entities.add(new EntitySnapshot(id, p.type().name(), cell.flags(), new Position(x, y), hasBuffs));
                    }
                    case Entity.BondedPair bp -> {
                        counts.merge(bp.primaryType().name(), 1, Integer::sum);
                        counts.merge(bp.secondaryType().name(), 1, Integer::sum);
                        entities.add(new EntitySnapshot(id, bp.primaryType().name(), cell.flags(), new Position(x, y), hasBuffs));
                    }
                    case Entity.CompositeMember cm -> {
                        counts.merge(cm.type().name(), 1, Integer::sum);
                        entities.add(new EntitySnapshot(id, cm.type().name(), cell.flags(), new Position(x, y), hasBuffs));
                    }
                    case Entity.Rock r -> { /* not species-counted */ }
                    case Entity.Nutrient n -> { /* not species-counted */ }
                }
            }
        }

        history.add(counts);
        ticks.add(currentTick);
        sessionCounts.add(sessionRegistry.getSessionCount());
        heapSamples.add(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        lastEntities = List.copyOf(entities);
    }

    public int tickCount() { return history.size(); }

    public long currentTick() { return ticks.isEmpty() ? 0L : ticks.get(ticks.size() - 1); }

    /**
     * BLOCKER 1 fix: tick value stored at sample index {@code i}. Used by
     * 16-06 {@code buildRunResult} when pairing {@code PopulationSample}
     * entries with their tick. Throws {@link IndexOutOfBoundsException}
     * if {@code i} is out of range.
     */
    public long tickAtIndex(int i) { return ticks.get(i); }

    public List<EntitySnapshot> latestEntities() { return lastEntities; }

    /** Extract the per-tick count series for one particle type (never null). */
    public List<Integer> typeSeries(String type) {
        List<Integer> out = new ArrayList<>(history.size());
        for (Map<String, Integer> row : history) {
            out.add(row.getOrDefault(type, 0));
        }
        return out;
    }

    /** Per-sample WS session count series. */
    public List<Integer> sessionCountSeries() { return List.copyOf(sessionCounts); }

    /** Alias kept for call-site ergonomics. */
    public List<Integer> activeSessionSeries() { return sessionCountSeries(); }

    /**
     * D-07 #1 — at every given checkpoint tick, all three types must have
     * strictly positive count. Returns true if no extinction is observed.
     */
    public boolean noExtinctionAtCheckpoints(long[] checkpoints) {
        for (long cp : checkpoints) {
            int idx = findSampleIndexAtOrAfter(cp);
            if (idx < 0 || idx >= history.size()) return false;
            Map<String, Integer> row = history.get(idx);
            if (row.getOrDefault("CATALYST", 0) <= 0) return false;
            if (row.getOrDefault("MEMBRANE", 0) <= 0) return false;
            if (row.getOrDefault("SPORE", 0) <= 0) return false;
        }
        return true;
    }

    /**
     * D-07 #2 — each type must sustain at least {@code minShare} of total
     * population for at least {@code minFraction} of sampled ticks.
     */
    public boolean typeFloorSatisfiedFor(double minShare, double minFraction) {
        if (history.isEmpty()) return false;
        int[] hits = new int[3]; // CATALYST, MEMBRANE, SPORE
        String[] keys = {"CATALYST", "MEMBRANE", "SPORE"};
        for (Map<String, Integer> row : history) {
            int total = row.getOrDefault("CATALYST", 0) + row.getOrDefault("MEMBRANE", 0) + row.getOrDefault("SPORE", 0);
            if (total <= 0) continue;
            for (int i = 0; i < 3; i++) {
                double share = row.getOrDefault(keys[i], 0) / (double) total;
                if (share >= minShare) hits[i]++;
            }
        }
        double n = history.size();
        for (int h : hits) {
            if (h / n < minFraction) return false;
        }
        return true;
    }

    /**
     * D-07 #3 — max across the three types of rolling amplitude
     * {@code (max - min) / mean} over any window of {@code windowSize}
     * consecutive samples. Returns empty if there aren't enough samples
     * or all windows had zero mean.
     */
    public OptionalDouble rollingAmplitude(int windowSize) {
        if (windowSize <= 1 || history.size() < windowSize) return OptionalDouble.empty();
        double best = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (String t : List.of("CATALYST", "MEMBRANE", "SPORE")) {
            List<Integer> series = typeSeries(t);
            for (int start = 0; start + windowSize <= series.size(); start++) {
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                long sum = 0L;
                for (int i = start; i < start + windowSize; i++) {
                    int v = series.get(i);
                    if (v < min) min = v;
                    if (v > max) max = v;
                    sum += v;
                }
                double mean = sum / (double) windowSize;
                if (mean <= 0.0) continue;
                double amp = (max - min) / mean;
                if (amp > best) { best = amp; any = true; }
            }
        }
        return any ? OptionalDouble.of(best) : OptionalDouble.empty();
    }

    /**
     * Lag-k autocorrelation of the given type's count series. Returns
     * empty if there are fewer than {@code lag + 2} samples or the
     * series variance is zero.
     */
    public OptionalDouble autocorrelation(String type, int lag) {
        List<Integer> s = typeSeries(type);
        int n = s.size();
        if (lag < 1 || n < lag + 2) return OptionalDouble.empty();
        double mean = 0.0;
        for (int v : s) mean += v;
        mean /= n;
        double num = 0.0, den = 0.0;
        for (int i = 0; i < n - lag; i++) {
            num += (s.get(i) - mean) * (s.get(i + lag) - mean);
        }
        for (int i = 0; i < n; i++) {
            double d = s.get(i) - mean;
            den += d * d;
        }
        if (den == 0.0) return OptionalDouble.empty();
        return OptionalDouble.of(num / den);
    }

    /**
     * REVIEWS HIGH #8 — scan lags {@code [minLag..maxLag]} across all three
     * types; return the MAX autocorrelation. Used by 16-06 D-04 #4 to avoid
     * locking a brittle fixed-lag assumption.
     */
    public OptionalDouble maxAutocorrelationOverLagRange(int minLag, int maxLag) {
        double best = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (String t : List.of("CATALYST", "MEMBRANE", "SPORE")) {
            for (int lag = minLag; lag <= maxLag; lag++) {
                OptionalDouble v = autocorrelation(t, lag);
                if (v.isPresent()) {
                    double val = v.getAsDouble();
                    if (val > best) { best = val; any = true; }
                }
            }
        }
        return any ? OptionalDouble.of(best) : OptionalDouble.empty();
    }

    /** The winning (type, lag, value) triple — recorded in the run fixture for traceability. */
    public record WinningLag(String type, int lag, double value) {}

    public Optional<WinningLag> maxAutocorrelationWithDetail(int minLag, int maxLag) {
        Optional<WinningLag> best = Optional.empty();
        for (String t : List.of("CATALYST", "MEMBRANE", "SPORE")) {
            for (int lag = minLag; lag <= maxLag; lag++) {
                OptionalDouble v = autocorrelation(t, lag);
                if (v.isPresent()) {
                    double val = v.getAsDouble();
                    if (best.isEmpty() || val > best.get().value()) {
                        best = Optional.of(new WinningLag(t, lag, val));
                    }
                }
            }
        }
        return best;
    }

    /**
     * Tick-pipeline drift as a percentage: how much the observed wall-clock
     * interval diverged from the configured {@code intervalMs * tickCount()}.
     * Positive = running slower than target.
     */
    public double tickDriftPercent(long intervalMs, long wallStartMs, long wallEndMs) {
        if (tickCount() <= 1) return 0.0;
        long expected = intervalMs * (tickCount() - 1);
        long actual = wallEndMs - wallStartMs;
        if (expected <= 0L) return 0.0;
        return (actual - expected) * 100.0 / expected;
    }

    /**
     * D-11 #4 — count of samples taken after {@code warmupSkip} ticks where
     * the active session count dipped below {@code expectedCount}. A stable
     * run should return 0.
     */
    public long steadyStateSessionDropouts(int warmupSkip, int expectedCount) {
        if (sessionCounts.size() != ticks.size()) {
            throw new IllegalStateException("sample() was not called with SessionRegistry");
        }
        long dropouts = 0L;
        for (int i = 0; i < ticks.size(); i++) {
            if (ticks.get(i) < warmupSkip) continue;
            if (sessionCounts.get(i) < expectedCount) dropouts++;
        }
        return dropouts;
    }

    /**
     * D-11 #5 — percent growth in mean heap usage between the window
     * starting at {@code firstWindowStart} and the window starting at
     * {@code lastWindowStart}, each of size {@code windowSize}.
     */
    public long heapGrowthPercent(long firstWindowStart, long lastWindowStart, int windowSize) {
        int firstIdx = findSampleIndexAtOrAfter(firstWindowStart);
        int lastIdx = findSampleIndexAtOrAfter(lastWindowStart);
        double meanFirst = heapSamples.stream().skip(firstIdx).limit(windowSize)
                .mapToLong(Long::longValue).average().orElse(0.0);
        double meanLast = heapSamples.stream().skip(lastIdx).limit(windowSize)
                .mapToLong(Long::longValue).average().orElse(0.0);
        if (meanFirst == 0.0) return 0L;
        return (long) ((meanLast - meanFirst) * 100.0 / meanFirst);
    }

    private int findSampleIndexAtOrAfter(long tick) {
        for (int i = 0; i < ticks.size(); i++) {
            if (ticks.get(i) >= tick) return i;
        }
        return ticks.isEmpty() ? -1 : ticks.size() - 1;
    }
}
