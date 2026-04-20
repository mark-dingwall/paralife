package com.paralife.codec;

import java.util.List;
import java.util.Optional;

/**
 * Compact wire frames per 15-SCHEMA.md §5 + §6.
 * Five permitted subtypes: r (register), S (sync), T (tick), a (action), E (error).
 */
public sealed interface Frame
        permits Frame.RegisterFrame, Frame.SyncFrame, Frame.TickFrame, Frame.ActionFrame, Frame.ErrorFrame {

    /** Client → Server. entityType ∈ {C, M, S}. */
    record RegisterFrame(char entityType) implements Frame {
        public RegisterFrame {
            if (entityType != 'C' && entityType != 'M' && entityType != 'S') {
                throw new IllegalArgumentException("entityType must be C/M/S: " + entityType);
            }
        }
    }

    /** Server → Client. Initial sync (no effects) or resync (with effects). */
    record SyncFrame(String entityId, List<ActiveEffect> effects) implements Frame {
        public SyncFrame {
            if (entityId == null || entityId.isEmpty()) {
                throw new IllegalArgumentException("entityId must not be blank");
            }
            effects = (effects == null) ? List.of() : List.copyOf(effects);
        }
    }

    /**
     * Server → Client per 15-SCHEMA.md §6.3.
     *
     * <p>{@code sensorRadius} is the minimal-form sentinel:
     * <ul>
     *   <li>{@code 0} — MINIMAL form (§6.3.2). Passive composite members
     *       (SENSOR, DEFENDER) receive this form. Frame carries alive-check +
     *       energy + own events only. Vision/effects/pool/roster are absent.
     *       On the wire the {@code sensorRadius} slot is omitted and minimal
     *       form is detected positionally by the codec.</li>
     *   <li>{@code 1} — 3×3 (authority-lite: FEEDER/ATTACKER/REPRODUCER).</li>
     *   <li>{@code 2} — 5×5 default (solo/bonded/LOCOMOTOR).</li>
     *   <li>{@code 3} — 7×7 with SENSOR_PLUS_1 buff active.</li>
     * </ul>
     * Any other value is rejected at construction.
     */
    record TickFrame(
            long tickId,
            int curX, int curY,
            int energy, int maxEnergy,
            int sensorRadius,
            List<CellEntry> cells,
            Optional<StateChange> change,
            List<ActiveEffect> effects,
            List<Event> events,
            Optional<PoolSnapshot> pool,
            List<RosterMember> roster
    ) implements Frame {
        public TickFrame {
            if (tickId < 0) throw new IllegalArgumentException("tickId negative: " + tickId);
            if (curX < 0 || curX > 4095) throw new IllegalArgumentException("curX out of range: " + curX);
            if (curY < 0 || curY > 4095) throw new IllegalArgumentException("curY out of range: " + curY);
            if (energy < 0) throw new IllegalArgumentException("energy negative: " + energy);
            if (maxEnergy < 0) throw new IllegalArgumentException("maxEnergy negative: " + maxEnergy);
            if (sensorRadius < 0 || sensorRadius > 3) {
                throw new IllegalArgumentException(
                        "sensorRadius must be 0 (minimal form) or 1..3 per 15-SCHEMA.md §6.3: " + sensorRadius);
            }
            cells = (cells == null) ? List.of() : List.copyOf(cells);
            effects = (effects == null) ? List.of() : List.copyOf(effects);
            events = (events == null) ? List.of() : List.copyOf(events);
            roster = (roster == null) ? List.of() : List.copyOf(roster);
            // Minimal-form invariant: sensorRadius==0 implies no vision/effects/pool/roster.
            if (sensorRadius == 0) {
                if (!cells.isEmpty() || change.isPresent() || !effects.isEmpty()
                        || pool.isPresent() || !roster.isEmpty()) {
                    throw new IllegalArgumentException(
                            "sensorRadius=0 (minimal form) must have empty cells/change/effects/pool/roster; only events allowed");
                }
            }
        }

        /** True when this is the passive-member minimal form (§6.3.2). */
        public boolean isMinimal() { return sensorRadius == 0; }
    }

    /** Client → Server per 15-SCHEMA.md §8.6. verb ∈ {M, E, A, R, V, L}. */
    record ActionFrame(char verb, Optional<String> arg) implements Frame {
        public ActionFrame {
            if (verb != 'M' && verb != 'E' && verb != 'A' && verb != 'R' && verb != 'V' && verb != 'L') {
                throw new IllegalArgumentException("verb must be M/E/A/R/V/L: " + verb);
            }
        }
    }

    /** Server → Client. 3-digit HTTP-style numeric code (e.g. 400, 429, 503). */
    record ErrorFrame(int code, Optional<String> message) implements Frame {
        public ErrorFrame {
            if (code < 100 || code > 999) {
                throw new IllegalArgumentException("code must be 3-digit: " + code);
            }
        }
    }
}
