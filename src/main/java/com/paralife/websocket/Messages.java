package com.paralife.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Base class for residual (not-yet-migrated) WebSocket message DTOs.
 *
 * <p><b>Plan 15-06 Task 2 Part D — PARTIAL STRIP.</b> The wire-bound records
 * whose consumers migrate in this plan have been removed:
 * {@code Welcome, Registered, Heartbeat, Register, Action, ActionResult,
 * Tick, CompositeAction, CompositeJoined}. Those paths now run on
 * {@link com.paralife.codec.Frame} via {@link com.paralife.codec.PerceptionCodec}.
 *
 * <p>The remaining DTOs ({@link CellView}, {@link Perception},
 * {@link EntityState}, {@link CompositePerception}) are used by
 * {@code PerceptionBroadcaster} / {@code HeuristicBrain} / {@code BotClient}
 * and the per-tick perception JSON path. Those consumers migrate in plans
 * 15-08 / 15-09; the final deletion of this file lands in plan 15-11 after
 * all consumers are fully migrated.
 *
 * <p>The {@code @JsonSubTypes} annotation is retained but reduced to the
 * subset that still ships over the JSON channel ({@link Perception} and
 * {@link CompositePerception}). {@link CellView} and {@link EntityState} are
 * embedded value types, not top-level messages.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Messages.Perception.class, name = "perception"),
        @JsonSubTypes.Type(value = Messages.CompositePerception.class, name = "composite_perception"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface Messages permits Messages.Perception, Messages.CompositePerception {

    // ── Server → Client ───────────────────────────────────────────

    /**
     * Server → Client: per-tick perception for a registered bot.
     * Contains the bot's own state and the local neighbourhood around it.
     */
    record Perception(
            long tickNumber,
            /** The bot's entity state. */
            EntityState self,
            /** Local neighbourhood cells, row-major from top-left. */
            List<List<CellView>> neighbourhood,
            /** Neighbourhood radius (e.g. 2 means a 5×5 grid centred on the entity). */
            int radius
    ) implements Messages {}

    // ── Composite entity messages ────────────────────────────────

    /**
     * Server -> Client: per-tick perception for composite members (D-36).
     * Contains the stitched neighbourhood from all SENSOR members.
     */
    record CompositePerception(
            long tickNumber,
            EntityState self,
            List<List<CellView>> stitchedNeighbourhood,
            int compositeSize,
            int sharedPoolEnergy,
            int maxPoolEnergy,
            String role
    ) implements Messages {}

    // ── Shared view types ─────────────────────────────────────────

    /**
     * Compact view of an entity's own state, sent inside {@link Perception}.
     */
    record EntityState(
            String entityId,
            String particleType,
            int energy,
            int maxEnergy,
            int x,
            int y
    ) {}

    /**
     * Compact view of a single cell in the neighbourhood.
     * Null occupantType means empty cell. "rock" / "nutrient" / particle type name for occupied.
     *
     * <p>Phase 13 Plan 02 adds {@code flags}, the bitfield of {@link com.paralife.world.Cell}
     * environmental flags (e.g. {@code FLAG_OVERCROWDED=1}, {@code FLAG_STARVING=2}).
     * Bots can inspect these to target weakened entities (cornered-animal D-10).
     *
     * <p>Phase 14 Plan 01 adds {@code cellStatus} and {@code entityStatus} byte
     * bitfields (D-36 through D-39). Projected from EnvironmentEngine's status
     * caches — not stored on {@link com.paralife.world.Cell} directly
     * (authoritative design decision per 14-01-PLAN.md <deviations>).
     *
     * <p><b>Phase 14 Plan 05 — {@code flags} vs {@code cellStatus} distinction
     * (cycle-6 MEDIUM #9):</b>
     * <ul>
     *   <li><b>{@code flags}</b> — the server-authoritative GLOBAL cell
     *       bitfield on {@link com.paralife.world.Cell}. Computed by
     *       {@link com.paralife.engine.SimulationEngine}'s global passes
     *       (overcrowding uses 8-neighbour Moore count against
     *       {@code SimulationConfig.overcrowdingThreshold()}; starvation
     *       tracks the occupant's energy). Identical for every bot.</li>
     *   <li><b>{@code cellStatus}</b> — vision-scoped, client-perspective
     *       bitfield recomposed PER BOT. Bit 0 (OVERCROWDED) in
     *       {@code cellStatus} is specifically recomputed from the neighbours
     *       a single bot can see — a cell globally overcrowded may present
     *       with {@code cellStatus} bit 0 = 0 for a bot whose vision covers
     *       only a subset of the cell's Moore neighbours. See D-40. Bits 1+
     *       (TOXIN_PRESENT, MUTAGEN_ZONE, ...) come unchanged from the
     *       EnvironmentEngine cell-status cache.</li>
     * </ul>
     * The {@code cellStatus} OVERCROWDED bit is thus vision-scoped and
     * per-bot; {@code flags}'s OVERCROWDED bit is server-authoritative and
     * global. Same underlying world-state, two different projections.
     *
     * <p><b>D-38 {@code cellStatus} bit layout:</b>
     * <pre>
     *   bit 0 (0x01) — OVERCROWDED      (vision-scoped per-bot; recomputed)
     *   bit 1 (0x02) — TOXIN_PRESENT    (from layer-2 cache; toxin &gt; threshold)
     *   bit 2 (0x04) — MUTAGEN_ZONE     (from layer-2 cache; mutagen grid != 0)
     *   bits 3-5     — reserved
     *   bits 6-7     — unused (byte sign)
     * </pre>
     *
     * <p><b>D-39 {@code entityStatus} bit layout:</b>
     * <pre>
     *   bit 0 (0x01) — STARVING (served via Cell.FLAG_STARVING, not projected here)
     *   bit 1 (0x02) — TOXIC         (occupant stands on toxic cell)
     *   bit 2 (0x04) — MUTATING      (active infection; BuffRegistry or infection map)
     *   bit 3 (0x08) — BUFFED        (active survivor buff)
     *   bits 4-5     — reserved
     *   bits 6-7     — unused (byte sign)
     * </pre>
     *
     * <p>Back-compat: 3-arg and 4-arg constructors preserved so Phase 13 tests
     * and callers continue to compile.
     *
     * @param occupantType   entity type name or {@code null} for empty
     * @param occupantId     entity id or {@code null} for empty
     * @param nutrientLevel  soil fertility level
     * @param flags          bitfield of Cell flags (0 = none) — server-authoritative GLOBAL state
     * @param cellStatus     6-bit projected cell-status bitfield (D-38) — vision-scoped per-bot
     * @param entityStatus   6-bit projected entity-status bitfield (D-39)
     */
    record CellView(
            String occupantType,
            String occupantId,
            int nutrientLevel,
            int flags,
            byte cellStatus,
            byte entityStatus
    ) {
        /** Back-compat 3-arg constructor — defaults {@code flags} and statuses to 0. */
        public CellView(String occupantType, String occupantId, int nutrientLevel) {
            this(occupantType, occupantId, nutrientLevel, 0, (byte) 0, (byte) 0);
        }

        /** Back-compat 4-arg constructor — defaults statuses to 0. */
        public CellView(String occupantType, String occupantId, int nutrientLevel, int flags) {
            this(occupantType, occupantId, nutrientLevel, flags, (byte) 0, (byte) 0);
        }
    }
}
