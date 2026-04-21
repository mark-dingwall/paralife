package com.paralife.engine.emergence;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Sliding-window observer: tracks short-lived "trigger" entities (starving
 * prey, buffed predator) and watches the surrounding observer-type density
 * for {@code windowTicks} ticks starting at the tick the trigger was first
 * seen. Each closed window becomes a
 * {@link RunFixtureWriter.RunResult.TriggerWindowResult} recording whether
 * the expected signal (predator cluster forming, prey fleeing) was held.
 *
 * <p>Design notes:
 * <ul>
 *   <li>REVIEWS HIGH #4/#9 — trigger predicates read {@link Entity#id()}
 *       through {@link PopulationHistory.EntitySnapshot#id()} directly; no
 *       position-indexed BotRegistry lookup. Plain
 *       {@link Entity.Particle} trigger candidates are fine because every
 *       permit of the sealed interface provides a stable id.</li>
 *   <li>Revision WARNING — factory signatures pin 6 args (including
 *       {@code gridWidth} + {@code gridHeight}) to match 16-06 Task 1
 *       call sites exactly. Grid dims are used by the toroidal Chebyshev
 *       distance calculation.</li>
 *   <li>Threat T-16-12 — STARVING read via {@code cell.flags() &
 *       Cell.FLAG_STARVING} (authoritative layer 1 state), never the
 *       wire bitmask.</li>
 * </ul>
 */
public class TriggerWatcher {

    private final Predicate<PopulationHistory.EntitySnapshot> trigger;
    private final Predicate<PopulationHistory.EntitySnapshot> observer;
    private final int windowTicks;
    private final int radius;
    private final int gridWidth;
    private final int gridHeight;
    private final double thresholdMargin;
    private final boolean directionUp;

    private final List<ActiveWindow> activeWindows = new ArrayList<>();
    private final List<RunFixtureWriter.RunResult.TriggerWindowResult> closedWindows = new ArrayList<>();

    private TriggerWatcher(Predicate<PopulationHistory.EntitySnapshot> trigger,
                           Predicate<PopulationHistory.EntitySnapshot> observer,
                           int windowTicks, int radius, int gridWidth, int gridHeight,
                           double thresholdMargin, boolean directionUp) {
        this.trigger = trigger;
        this.observer = observer;
        this.windowTicks = windowTicks;
        this.radius = radius;
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
        this.thresholdMargin = thresholdMargin;
        this.directionUp = directionUp;
    }

    /**
     * Starving-prey factory: a starving prey entity should attract predators
     * of the given type (density rises). PINNED 6-arg signature per
     * revision WARNING — {@code W}/{@code R} plus grid dims.
     */
    public static TriggerWatcher forStarvingPrey(Entity.ParticleType preyType, Entity.ParticleType predatorType, int W, int R, int gridWidth, int gridHeight) {
        return new TriggerWatcher(
                e -> e.type().equals(preyType.name()) && (e.flags() & Cell.FLAG_STARVING) != 0,
                e -> e.type().equals(predatorType.name()),
                W, R, gridWidth, gridHeight,
                /* thresholdMargin */ 0.5,
                /* directionUp */ true);
    }

    /**
     * Buffed-predator factory (inverted flee signal): when a predator of the
     * given type has an active buff, prey of the paired type should flee —
     * observer density is expected to drop. PINNED 6-arg signature matching
     * {@link #forStarvingPrey}.
     *
     * <p>REVIEWS HIGH #4: no "stable IDs only" scoping. Every permit of
     * {@link Entity} — including plain {@link Entity.Particle} — returns a
     * non-null id on the sealed interface (verified at Entity.java:20), so
     * hasBuffs() is sufficient discrimination.
     */
    public static TriggerWatcher forBuffedPredator(Entity.ParticleType predatorType, Entity.ParticleType preyType, int W, int R, int gridWidth, int gridHeight) {
        return new TriggerWatcher(
                e -> e.type().equals(predatorType.name()) && e.hasBuffs(),
                e -> e.type().equals(preyType.name()),
                W, R, gridWidth, gridHeight,
                /* thresholdMargin */ 0.5,
                /* directionUp */ false);
    }

    /**
     * Called once per sampling-loop iteration. Opens a window for any newly
     * visible trigger, samples all active windows, and closes windows that
     * have reached {@code windowTicks} in length.
     *
     * <p>The {@code WorldGrid} parameter is accepted for future extension
     * (e.g. direct cell inspection); current implementation relies purely
     * on the snapshot already captured by {@link PopulationHistory#sample}.
     */
    public void tickIfWindowActive(PopulationHistory history, WorldGrid grid) {
        long tick = history.currentTick();
        List<PopulationHistory.EntitySnapshot> snapshot = history.latestEntities();

        // Open new windows for any trigger entities not already tracked
        for (PopulationHistory.EntitySnapshot e : snapshot) {
            if (!trigger.test(e)) continue;
            boolean alreadyOpen = activeWindows.stream().anyMatch(w -> w.triggerId.equals(e.id()));
            if (alreadyOpen) continue;
            double baseline = computeCurrentObserverDensity(e, snapshot);
            activeWindows.add(new ActiveWindow(e.id(), e.type(), e.position(), tick, baseline));
        }

        // Sample each open window; close any that have reached windowTicks
        Iterator<ActiveWindow> it = activeWindows.iterator();
        while (it.hasNext()) {
            ActiveWindow w = it.next();
            int observerCount = countObservers(w.center, snapshot);
            w.samples.add(observerCount);
            if (tick - w.startTick >= windowTicks) {
                double mean = w.samples.stream().mapToInt(Integer::intValue).average().orElse(0.0);
                boolean signalHeld = directionUp
                        ? mean > w.baseline + thresholdMargin
                        : mean < w.baseline - thresholdMargin;
                closedWindows.add(new RunFixtureWriter.RunResult.TriggerWindowResult(
                        w.triggerId, w.triggerType, w.startTick,
                        w.samples.size(), mean, w.baseline, signalHeld));
                it.remove();
            }
        }
    }

    private int countObservers(Position center, List<PopulationHistory.EntitySnapshot> snapshot) {
        int count = 0;
        for (PopulationHistory.EntitySnapshot s : snapshot) {
            if (!observer.test(s)) continue;
            if (chebyshevToroidal(center, s.position()) <= radius) count++;
        }
        return count;
    }

    private double computeCurrentObserverDensity(PopulationHistory.EntitySnapshot triggerSnap,
                                                  List<PopulationHistory.EntitySnapshot> snapshot) {
        return countObservers(triggerSnap.position(), snapshot);
    }

    /** FertilityInitializer:80-81 idiom — toroidal Chebyshev distance with ctor-injected dims. */
    private int chebyshevToroidal(Position a, Position b) {
        int dxWrapped = Math.min(
                Math.floorMod(a.x() - b.x(), gridWidth),
                Math.floorMod(b.x() - a.x(), gridWidth));
        int dyWrapped = Math.min(
                Math.floorMod(a.y() - b.y(), gridHeight),
                Math.floorMod(b.y() - a.y(), gridHeight));
        return Math.max(dxWrapped, dyWrapped);
    }

    /** All windows that have closed (reached windowTicks) — fed into run fixture. */
    public List<RunFixtureWriter.RunResult.TriggerWindowResult> results() {
        return List.copyOf(closedWindows);
    }

    /** Count of closed windows where the expected directional signal held. */
    public long signalHeldCount() {
        return closedWindows.stream().filter(r -> r.signalHeld()).count();
    }

    private static final class ActiveWindow {
        final String triggerId;
        final String triggerType;
        final Position center;
        final long startTick;
        final double baseline;
        final List<Integer> samples = new ArrayList<>();

        ActiveWindow(String id, String type, Position center, long startTick, double baseline) {
            this.triggerId = id;
            this.triggerType = type;
            this.center = center;
            this.startTick = startTick;
            this.baseline = baseline;
        }
    }
}
