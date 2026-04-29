package com.paralife.admission;

import com.paralife.bot.BotIdentity;
import com.paralife.engine.TickEngine;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single source of truth for source/harness tag derivation (Phase 18, D-11 / D-12).
 *
 * <h2>Overflow-folding registry</h2>
 * <p>The tagger owns a cardinality-bounded set of observed harness ids. Once
 * {@link #maxCardinality} unique ids have been observed, every subsequently seen id
 * (including unknown ones) folds to {@code "overflow"} as a Micrometer tag value.
 * The MeterFilter registered in {@link AdmissionMetrics} acts as defense-in-depth,
 * but the primary folding happens here — ensuring that map-side bucket keys and
 * registry-side tag values are always in sync.
 *
 * <h2>Round 2 amendments</h2>
 * <ul>
 *   <li><b>Codex HIGH (warn-once at fold site):</b> the warn-once HARNESS overflow log line
 *       is emitted inside {@link #foldHarnessIfOverCap} where the raw harness id is still in
 *       scope — NOT in the MeterFilter which only sees {@code harness=overflow}.</li>
 *   <li><b>Claude+Codex MEDIUM (synchronized slot-claim):</b> the
 *       {@code containsKey + add + size-check} slot-claim is wrapped in a single
 *       {@code synchronized (slotLock)} block, eliminating the cap-boundary race where two
 *       threads could each observe {@code size < maxCardinality} and both insert, yielding
 *       {@code size == maxCardinality + 1}.</li>
 * </ul>
 */
public final class AttributionTagger {

    private static final Logger log = LoggerFactory.getLogger(AttributionTagger.class);

    /** Session attribute key storing the source taxonomy value (e.g. {@code "harness"}). */
    public static final String ATTR_SOURCE  = "source";
    /** Session attribute key storing the harness id (only when {@code source=harness}). */
    public static final String ATTR_HARNESS = "harness";

    /** Bounded source taxonomy — mirrors {@link BotIdentity#SOURCE_TAXONOMY}. */
    public static final Set<String> SOURCE_TAXONOMY = BotIdentity.SOURCE_TAXONOMY;

    // ── Slot-claim state ─────────────────────────────────────────────────────

    /**
     * Single mutex protecting {@link #observedHarnessIds}.
     * Round 2 Claude+Codex MEDIUM: a synchronized block eliminates the
     * putIfAbsent + incrementAndGet rollback race at the cap boundary.
     */
    private final Object slotLock = new Object();
    private final Set<String> observedHarnessIds = new LinkedHashSet<>();
    private final int maxCardinality;

    /** Optional TickEngine for the warn-once log's tick= field. */
    private final TickEngine tickEngine;

    /** Warn-once gate: emits exactly one WARN log line on the first overflow. */
    private final AtomicBoolean overflowWarned = new AtomicBoolean(false);

    public AttributionTagger(int maxCardinality, TickEngine tickEngine) {
        if (maxCardinality < 1) {
            throw new IllegalArgumentException(
                    "maxCardinality must be >= 1 (got " + maxCardinality + ")");
        }
        this.maxCardinality = maxCardinality;
        this.tickEngine = tickEngine;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Derive Micrometer {@link Tags} for the given session.
     *
     * <ul>
     *   <li>{@code null} session → {@code Tags.of("source", "unknown")}</li>
     *   <li>{@code source=harness, harness=<id>} → two tags; harness id folds to
     *       {@code "overflow"} if the cardinality cap has been reached.</li>
     *   <li>any other source → single {@code source} tag (no harness tag emitted).</li>
     * </ul>
     */
    public Tags tagsFor(WebSocketSession session) {
        if (session == null) {
            return Tags.of("source", "unknown");
        }
        String source = sourceOf(session);
        String harness = harnessOf(session);
        // Defense-in-depth (Codex Round 2 #5): only emit harness tag when source=harness AND
        // harness present. Prevents drift if an attribute layer ever sets harness on a
        // non-harness source.
        if (harness == null || !"harness".equals(source)) {
            return Tags.of("source", source);
        }
        String effective = foldHarnessIfOverCap(harness);
        return Tags.of("source", source, "harness", effective);
    }

    /**
     * Format {@code source=<v>[ harness=<id>]} as a space-separated log field string.
     * Matches the Phase 17 grep-friendly low-cardinality log marker style (D-13).
     *
     * <p>This method does NOT fold the harness id — it reads the raw session attribute
     * value, suitable for log emission where the full id is useful.
     */
    public static String formatLogFields(WebSocketSession session) {
        String source = sourceOf(session);
        String harness = harnessOf(session);
        return harness == null
                ? "source=" + source
                : "source=" + source + " harness=" + harness;
    }

    // ── Static extraction helpers ─────────────────────────────────────────────

    public static String sourceOf(WebSocketSession session) {
        if (session == null) return "unknown";
        Object v = session.getAttributes().get(ATTR_SOURCE);
        if (v instanceof String s && SOURCE_TAXONOMY.contains(s)) return s;
        return "unknown";
    }

    public static String harnessOf(WebSocketSession session) {
        if (session == null) return null;
        Object v = session.getAttributes().get(ATTR_HARNESS);
        return v instanceof String s ? s : null;
    }

    // ── Overflow folding ──────────────────────────────────────────────────────

    /**
     * Fold the harness id to {@code "overflow"} if the cardinality cap has been reached.
     *
     * <p>Round 2 amendments applied here:
     * <ul>
     *   <li><b>Codex HIGH:</b> warn-once log is emitted HERE, where the raw harness id
     *       is still in scope. The MeterFilter only sees {@code harness=overflow} and
     *       therefore cannot identify the real 65th harness id.</li>
     *   <li><b>Claude+Codex MEDIUM:</b> entire slot-claim is inside {@code synchronized (slotLock)}
     *       to eliminate the race condition where two threads both observe
     *       {@code size < maxCardinality} and both insert, yielding {@code size == cap + 1}.</li>
     * </ul>
     *
     * @param harnessId raw harness id from session attributes
     * @return the harness id as-is if within cap, or {@code "overflow"} otherwise
     */
    String foldHarnessIfOverCap(String harnessId) {
        synchronized (slotLock) {
            // Fast path: already observed — return immediately without side effects.
            if (observedHarnessIds.contains(harnessId)) {
                return harnessId;
            }
            // Slot available: claim it.
            if (observedHarnessIds.size() < maxCardinality) {
                observedHarnessIds.add(harnessId);
                return harnessId;
            }
            // Cap reached. Fold this id to "overflow".
            // Round 2 Codex HIGH: warn-once log fires HERE — harnessId is the real 65th id.
            if (overflowWarned.compareAndSet(false, true)) {
                long tick = tickEngine != null ? tickEngine.currentTick() : -1L;
                String truncated = harnessId.length() > 32
                        ? harnessId.substring(0, 32)
                        : harnessId;
                log.warn("HARNESS overflow first-seen tick={} harness-id={}", tick, truncated);
            }
            return "overflow";
        }
    }
}
