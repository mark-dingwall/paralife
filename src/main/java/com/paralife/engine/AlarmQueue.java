package com.paralife.engine;

import com.paralife.world.Position;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-composite alarm sink. Populated by {@link ActionResolver}'s verb-L
 * dispatch; drained by {@code TickBroadcaster.buildTickFrame} (wiring in plan
 * 15-08) when it builds the LOCOMOTOR's next {@code v} block and emits
 * {@code vN<relCoord>} events per SCHEMA §8.4.
 *
 * <p>Created in plan 15-06 (NOT plan 15-08) so that verb-L dispatch has a
 * target from Wave 3 onward — no silent no-op window while the broadcaster
 * rewrite is still in flight. The Wave 3 implementation is intentionally
 * minimal — a thread-safe per-composite queue. Plan 15-08 Task 2 wires the
 * drain into {@code buildTickFrame}.
 */
@Component
public class AlarmQueue {

    /** Point-in-time alarm record (per SCHEMA §8.4 {@code v<coord>N}). */
    public record AlarmEntry(String compositeId, Position alarmingCellAbs, long tickSubmitted) {}

    private final Map<String, Queue<AlarmEntry>> pending = new ConcurrentHashMap<>();

    /**
     * Called from {@link ActionResolver}'s verb-L dispatch when a composite
     * member sends {@code a|L}. Null {@code compositeId} is a no-op (solo
     * entity — alarm simply never surfaces to a roster).
     */
    public void enqueueAlarm(String compositeId, Position alarmingCellAbs, long tick) {
        if (compositeId == null) return;
        pending.computeIfAbsent(compositeId, k -> new ConcurrentLinkedQueue<>())
                .add(new AlarmEntry(compositeId, alarmingCellAbs, tick));
    }

    /**
     * Drain all alarms for a composite. Caller is the LOCOMOTOR frame builder
     * (plan 15-08). Returns an empty list when nothing is pending.
     */
    public List<AlarmEntry> drainAlarms(String compositeId) {
        Queue<AlarmEntry> q = pending.get(compositeId);
        if (q == null || q.isEmpty()) return List.of();
        List<AlarmEntry> out = new ArrayList<>(q.size());
        AlarmEntry e;
        while ((e = q.poll()) != null) out.add(e);
        return out;
    }
}
