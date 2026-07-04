package com.paralife.admission;

import com.paralife.engine.TickEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Operator-visibility metrics bean for Phase 18 attribution (D-12 / D-17 / D-18).
 *
 * <h2>Two-tag counters and gauges</h2>
 * <p>All admission and backpressure metrics gain {@code source[, harness]} tags
 * derived from session attributes via {@link AttributionTagger}. Exceptions (D-12):
 * <ul>
 *   <li>{@link #M_MAINTENANCE} — stays scalar (server-global, not per-source)</li>
 *   <li>{@link #M_TICK_WORK_MS} — stays scalar (tick health is a server property)</li>
 * </ul>
 *
 * <h2>Per-bucket gauge lifecycle</h2>
 * <p>Active and stalled session counts are tracked per attribution bucket:
 * <ul>
 *   <li>{@link #incActiveBucket(WebSocketSession)} — called at Allow path; reads entityId from session attrs.</li>
 *   <li>{@link #decActiveBucket(WebSocketSession)} / {@link #decActiveBucketByTags(Tags)} — called at cleanup.</li>
 *   <li>{@link #incStalledBucket(WebSocketSession, String)} — Round 2 Claude HIGH: entityId param is explicit
 *       so callers can pass it BEFORE {@code attrs.remove(ATTR_ENTITY_ID)} in markStalled.</li>
 *   <li>{@link #decStalledBucket(WebSocketSession)} / {@link #decStalledBucketByTags(Tags)} — called at rebind/expiry.</li>
 *   <li>{@link #lookupBucketTags(String)} — returns the Tags captured at incActiveBucket/incStalledBucket time;
 *       used by grace-expiry reapers that have no WebSocketSession.</li>
 * </ul>
 *
 * <h2>MeterFilter defense-in-depth</h2>
 * <p>{@link MeterFilter#maximumAllowableTags} is registered on both
 * {@code paralife.admission.*} and {@code paralife.backpressure.*} prefixes as a
 * second line of defense after {@link AttributionTagger}'s primary overflow folding.
 *
 * <h2>Round 2 amendments</h2>
 * <ul>
 *   <li><b>Claude HIGH:</b> {@code incStalledBucket(WebSocketSession, String)} takes explicit entityId
 *       so the {@code bucketTagsByEntityId} snapshot is captured with the real entityId even if
 *       {@code attrs.remove(ATTR_ENTITY_ID)} has already run in the caller.</li>
 *   <li><b>OpenCode HIGH:</b> Constructor is 4-arg; all six existing test files updated accordingly.</li>
 *   <li><b>Codex HIGH:</b> {@link AttributionTagger#foldHarnessIfOverCap} emits the warn-once log
 *       with the raw 65th harness id before folding to "overflow" — not surfaced here.</li>
 * </ul>
 */
@Component
public class AdmissionMetrics {

    private static final Logger log = LoggerFactory.getLogger(AdmissionMetrics.class);

    // ── Metric names ─────────────────────────────────────────────────────────

    public static final String M_REJECTED           = "paralife.admission.rejected";
    public static final String M_INGRESS_OVERWRITES = "paralife.admission.ingress.overwrites";
    public static final String M_ACTIVE_ENTITIES    = "paralife.admission.active.entities";
    public static final String M_MAINTENANCE        = "paralife.admission.maintenance";
    public static final String M_TICK_WORK_MS       = "paralife.tick.health.work-time-ms";
    public static final String M_STALLED_SESSIONS   = "paralife.backpressure.stalled.sessions";
    public static final String M_FRAME_SIZE         = "paralife.outbound.frame.size.bytes";
    public static final String M_REBOUND            = "paralife.backpressure.rebound";
    public static final String M_TERMINAL_DROPOUT   = "paralife.backpressure.terminal.dropouts";
    public static final String M_STALLED_TOTAL      = "paralife.backpressure.stalled.total";
    /** Phase 19 SCALE-06 (REVIEWS LOW-12): bounded lost-race retries counter. */
    public static final String M_PLACEMENT_LOST_RACE = "paralife.placement.lost-race.total";
    /** Phase 19.1 D-14: drain VT join timed out. */
    public static final String M_DETACH_TIMEOUT = "paralife.outbound.detach.timeout";
    /** Phase 20-01c (F2 review remediation): peak per-session outbound queue depth across all attached sessions. */
    public static final String M_OUTBOUND_QUEUE_DEPTH_MAX = "paralife.outbound.queue.depth.max";
    /** Phase 20-01c (F2 review remediation): per-frame encode + sendMessage latency Timer. */
    public static final String M_OUTBOUND_ENCODE_SEND_MS  = "paralife.outbound.encode.send.ms";
    /** Phase 21 scale-benchmark: per-tick work-time distribution (drift proxy). */
    public static final String METRIC_TICK_DRIFT = "paralife.tick.drift.millis";

    /** Session attribute key for entity id — shared constant for callers that need it. */
    public static final String ATTR_ENTITY_ID = "entityId";

    // ── Dependencies ─────────────────────────────────────────────────────────

    private final MeterRegistry registry;
    private final AttributionTagger tagger;

    // ── Scalar gauges (D-12: maintenance and tick-work stay scalar) ───────────

    private final AtomicInteger maintenance    = new AtomicInteger();
    private final AtomicLong    lastTickWorkMs = new AtomicLong();

    // ── Per-bucket gauge state ────────────────────────────────────────────────

    /** Maps Tags → active entity count per attribution bucket. */
    private final ConcurrentHashMap<Tags, AtomicInteger> activeBuckets  = new ConcurrentHashMap<>();
    /** Maps Tags → stalled session count per attribution bucket. */
    private final ConcurrentHashMap<Tags, AtomicInteger> stalledBuckets = new ConcurrentHashMap<>();

    /**
     * Snapshot of attribution Tags per entityId. Written at
     * {@link #incActiveBucket}/{@link #incStalledBucket} time; consulted by
     * {@link #cleanupByEntityId}-style callers and grace-expiry reapers that have no
     * WebSocketSession available. Mirrors {@code respawnCountAtStall} pattern from Phase 17.
     */
    private final ConcurrentHashMap<String, Tags> bucketTagsByEntityId = new ConcurrentHashMap<>();

    // ── Scalar counters ──────────────────────────────────────────────────────

    private final Counter rebound;
    private final Counter terminalDropouts;
    private final Counter stalledTotal;
    private final DistributionSummary frameSize;
    /** Phase 19 SCALE-06 (REVIEWS LOW-12): placement lost-race counter. */
    private final Counter lostRace;
    /** Phase 19.1 D-14: drain VT did not exit within join timeout. */
    private final Counter detachTimeout;
    /** Phase 20-01c (F2 remediation): per-frame encode + sendMessage latency Timer. */
    private final Timer encodeSendTimer;
    /** Phase 21 scale-benchmark: per-tick work-time distribution (drift proxy). */
    private final DistributionSummary tickDrift;

    // F2/A1 remediation: strong reference so Micrometer's weak-target gauge doesn't GC the supplier.
    private volatile java.util.function.IntSupplier outboundQueueDepthSupplier;

    // ── Constructor ──────────────────────────────────────────────────────────

    /**
     * Four-arg constructor (Round 2 OpenCode HIGH — constructor breaking change).
     *
     * <p>Registers:
     * <ol>
     *   <li>{@link MeterFilter#maximumAllowableTags} on {@code paralife.admission.*} and
     *       {@code paralife.backpressure.*} as defense-in-depth against cardinality explosion.</li>
     *   <li>Scalar gauges for maintenance and tick-work (D-12 invariants).</li>
     *   <li>Scalar counters for rebound, terminal-dropout, stalled-total, frame-size.</li>
     * </ol>
     */
    @Autowired
    public AdmissionMetrics(MeterRegistry registry,
                            AdmissionConfig admissionConfig,
                            TickEngine tickEngine,
                            AttributionTagger tagger) {
        this.registry = registry;
        this.tagger   = tagger;

        int cap = admissionConfig.attribution().maxHarnessCardinality();

        // MeterFilter defense-in-depth: cap harness tag cardinality on both prefixes.
        // The primary folding lives in AttributionTagger.foldHarnessIfOverCap; this filter
        // is a second safety net. We allow cap+1 so the "overflow" bucket can register
        // alongside the cap legitimate harness buckets — the tagger ensures no more than
        // cap+1 distinct harness values ever reach the registry.
        registry.config()
                .meterFilter(MeterFilter.maximumAllowableTags(
                        "paralife.admission", "harness", cap + 1, MeterFilter.deny()))
                .meterFilter(MeterFilter.maximumAllowableTags(
                        "paralife.backpressure", "harness", cap + 1, MeterFilter.deny()));

        // Scalar D-12 gauges (no source/harness tags).
        Gauge.builder(M_MAINTENANCE, maintenance, AtomicInteger::get)
                .description("Maintenance flag mirror: 0=off, 1=on (D-18 scalar)")
                .register(registry);
        Gauge.builder(M_TICK_WORK_MS, lastTickWorkMs, AtomicLong::get)
                .description("Most-recently-completed tick wall-clock work time in ms (D-18 scalar)")
                .register(registry);

        // Scalar counters.
        this.rebound = Counter.builder(M_REBOUND)
                .description("STALLED sessions that successfully reconnected with their resume token")
                .register(registry);
        this.stalledTotal = Counter.builder(M_STALLED_TOTAL)
                .description("Sessions that transitioned into STALLED grace")
                .register(registry);
        this.terminalDropouts = Counter.builder(M_TERMINAL_DROPOUT)
                .description("STALLED sessions whose resume token expired before reconnect")
                .register(registry);
        this.frameSize = DistributionSummary.builder(M_FRAME_SIZE)
                .description("Encoded outbound frame size in bytes")
                .baseUnit("bytes")
                .register(registry);
        // Phase 19 SCALE-06 (REVIEWS LOW-12): placement lost-race retry counter.
        this.lostRace = Counter.builder(M_PLACEMENT_LOST_RACE)
                .description("Placement: sampled cell lost the trySetEntity race (bounded 3-retry)")
                .register(registry);
        // Phase 19.1 D-14: detach-timeout counter.
        this.detachTimeout = Counter.builder(M_DETACH_TIMEOUT)
                .description("OutboundSender.detachSession join timed out (drain VT did not exit within 100ms)")
                .register(registry);
        // Phase 20-01c (F2 remediation): per-frame encode + sendMessage latency.
        // Tick-work gauge ends at @Order(50) frame build; this Timer surfaces the per-connection
        // encode + sendMessage cost that lives on per-session VTs outside the tick window.
        this.encodeSendTimer = Timer.builder(M_OUTBOUND_ENCODE_SEND_MS)
                .description("OutboundSender.drainLoop encode + sendMessage latency (per frame)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        // Phase 21 scale-benchmark: per-tick work-time distribution (drift proxy).
        this.tickDrift = DistributionSummary.builder(METRIC_TICK_DRIFT)
                .baseUnit("milliseconds")
                .description("Per-tick work-time distribution (scale-benchmark tick drift)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /**
     * Back-compat single-arg constructor for pre-Phase-18 tests.
     * Delegates with {@link AdmissionConfig#defaults()}, null tickEngine, and a default tagger.
     */
    public AdmissionMetrics(MeterRegistry registry) {
        this(registry, AdmissionConfig.defaults(), null,
                new AttributionTagger(64, null));
    }

    // ── Rejected counter (two-tag) ────────────────────────────────────────────

    /**
     * Increment the tagged rejection counter. Emits {@code reason + source[, harness]} tags
     * (extends Phase 17 D-17 single-tag shape).
     */
    public void incRejected(String reason, WebSocketSession session) {
        Counter.builder(M_REJECTED)
                .tag("reason", reason)
                .tags(tagger.tagsFor(session))
                .description("Admission rejections by reason+source (Phase 18 D-12)")
                .register(registry)
                .increment();
    }

    /** Back-compat single-arg shim → {@link #incRejected(String, WebSocketSession)} with null session. */
    public void incRejected(String reason) {
        incRejected(reason, null);
    }

    // ── Ingress overwrites counter (two-tag) ─────────────────────────────────

    /**
     * Increment the aggregate ingress-overwrite counter with source/harness tags (D-09 / D-12).
     * Session may be null (→ source=unknown).
     */
    public void incIngressOverwrite(WebSocketSession session) {
        Counter.builder(M_INGRESS_OVERWRITES)
                .tags(tagger.tagsFor(session))
                .description("Action-frame ingress overwrites (last-write-wins collapse per D-09)")
                .register(registry)
                .increment();
    }

    /** Back-compat no-arg shim → {@link #incIngressOverwrite(WebSocketSession)} with null. */
    public void incIngressOverwrite() {
        incIngressOverwrite(null);
    }

    // ── Active bucket (per attribution bucket gauge) ──────────────────────────

    /**
     * Increment the active-entities gauge for this session's attribution bucket.
     * Also captures entityId → Tags snapshot in {@link #bucketTagsByEntityId}.
     * Called at the Allow/register path.
     */
    public void incActiveBucket(WebSocketSession session) {
        Tags tags = tagger.tagsFor(session);
        activeBuckets.computeIfAbsent(tags, t -> {
            AtomicInteger ai = new AtomicInteger();
            Gauge.builder(M_ACTIVE_ENTITIES, ai, AtomicInteger::get)
                    .tags(t)
                    .description("Live cap-relevant occupants per source[, harness] (D-18)")
                    .register(registry);
            return ai;
        }).incrementAndGet();

        // Capture entityId → Tags snapshot for cleanupByEntityId/grace-expiry reapers.
        if (session != null) {
            Object eidObj = session.getAttributes().get(ATTR_ENTITY_ID);
            if (eidObj instanceof String eid) {
                bucketTagsByEntityId.put(eid, tags);
            }
        }
    }

    /** Decrement active bucket for this session's attribution bucket. */
    public void decActiveBucket(WebSocketSession session) {
        decActiveBucketByTags(tagger.tagsFor(session));
    }

    /**
     * Decrement active bucket by previously-captured Tags.
     * Used by {@code cleanupByEntityId} and grace-expiry reapers that have no session.
     */
    public void decActiveBucketByTags(Tags tags) {
        if (tags == null) return;
        AtomicInteger ai = activeBuckets.get(tags);
        if (ai != null) ai.decrementAndGet();
    }

    // ── Stalled bucket (per attribution bucket gauge) ─────────────────────────

    /**
     * Increment the stalled-sessions gauge for this session's attribution bucket.
     *
     * <p><b>Round 2 Claude HIGH amendment:</b> takes {@code entityId} explicitly.
     * Caller ({@code WorldWebSocketHandler.markStalled}) MUST pass entityId
     * BEFORE calling {@code attrs.remove(ATTR_ENTITY_ID)}, otherwise the
     * {@link #bucketTagsByEntityId} snapshot would receive null and the grace-expiry
     * reaper would have no Tags to decrement.
     *
     * @param session  the STALLED session (provides attribution tags)
     * @param entityId the entity id captured by the caller BEFORE attrs.remove
     */
    public void incStalledBucket(WebSocketSession session, String entityId) {
        Tags tags = tagger.tagsFor(session);
        stalledBuckets.computeIfAbsent(tags, t -> {
            AtomicInteger ai = new AtomicInteger();
            Gauge.builder(M_STALLED_SESSIONS, ai, AtomicInteger::get)
                    .tags(t)
                    .description("STALLED sessions in grace window per source[, harness] (D-18)")
                    .register(registry);
            return ai;
        }).incrementAndGet();

        // Use the EXPLICIT entityId param — session attrs may have already been cleared.
        if (entityId != null) {
            bucketTagsByEntityId.put(entityId, tags);
        }
    }

    /** Decrement stalled bucket for this session's attribution bucket. */
    public void decStalledBucket(WebSocketSession session) {
        decStalledBucketByTags(tagger.tagsFor(session));
    }

    /**
     * Decrement stalled bucket by previously-captured Tags.
     * Used by rebind path and grace-expiry reapers.
     */
    public void decStalledBucketByTags(Tags tags) {
        if (tags == null) return;
        AtomicInteger ai = stalledBuckets.get(tags);
        if (ai != null) ai.decrementAndGet();
    }

    /**
     * Look up the attribution Tags captured at {@link #incActiveBucket} or
     * {@link #incStalledBucket} time for the given entityId.
     *
     * @param entityId the entity id
     * @return captured Tags, or {@code null} if not found
     */
    public Tags lookupBucketTags(String entityId) {
        return entityId == null ? null : bucketTagsByEntityId.get(entityId);
    }

    /**
     * Release the {@link #bucketTagsByEntityId} snapshot for the given entityId.
     *
     * <p>Without this call the map grows unbounded across long-running churn
     * (every entity gets a fresh id, including respawn {@code -rN} suffixes).
     * Callers must invoke this once per entityId after all bucket decrements
     * have been made via the snapshot tags.
     *
     * <p>Idempotent: a second call with the same entityId is a no-op.
     */
    public void releaseBucketTags(String entityId) {
        if (entityId == null) return;
        bucketTagsByEntityId.remove(entityId);
    }

    /**
     * Phase 19.1 D-08 — rewrite the {@link #bucketTagsByEntityId} entry under
     * {@code newEntityId} using the Tags previously captured for {@code oldEntityId}.
     * Mirrors {@link ResumeTokenRegistry#remapEntity}. Idempotent; no-op when no
     * snapshot exists for {@code oldEntityId} or when ids match.
     */
    public void remapBucketTags(String oldEntityId, String newEntityId) {
        if (oldEntityId == null || newEntityId == null) return;
        if (oldEntityId.equals(newEntityId)) return;
        Tags tags = bucketTagsByEntityId.remove(oldEntityId);
        if (tags != null) {
            bucketTagsByEntityId.put(newEntityId, tags);
        }
    }

    /**
     * Test-only accessor: number of entityId snapshots currently held.
     * Used by lifecycle invariant tests across multiple packages.
     */
    public int bucketTagsSize() {
        return bucketTagsByEntityId.size();
    }

    /** Test-only: total of every active bucket gauge value. */
    public int totalActiveBucketCount() {
        return activeBuckets.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /** Test-only: total of every stalled bucket gauge value. */
    public int totalStalledBucketCount() {
        return stalledBuckets.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /** Test-only: minimum value across all active buckets (catches double-decrement). */
    public int minActiveBucketCount() {
        return activeBuckets.values().stream().mapToInt(AtomicInteger::get).min().orElse(0);
    }

    /** Test-only: minimum value across all stalled buckets. */
    public int minStalledBucketCount() {
        return stalledBuckets.values().stream().mapToInt(AtomicInteger::get).min().orElse(0);
    }

    /** Test-only: number of distinct active-bucket Tags keys. */
    public int activeBucketKeyCount() {
        return activeBuckets.size();
    }

    /** Test-only: snapshot of active-bucket Tags keys (for harness invariant test). */
    public java.util.Set<Tags> activeBucketKeys() {
        return java.util.Set.copyOf(activeBuckets.keySet());
    }

    /** Test-only: snapshot of stalled-bucket Tags keys (for harness invariant test). */
    public java.util.Set<Tags> stalledBucketKeys() {
        return java.util.Set.copyOf(stalledBuckets.keySet());
    }

    // ── Scalar gauges (D-12: no source/harness tags) ─────────────────────────

    /** Mirror the maintenance flag as 0 (off) or 1 (on) in the gauge (D-18 scalar). */
    public void setMaintenance(boolean on) { maintenance.set(on ? 1 : 0); }

    /** Set the most-recently-completed tick work time in ms (D-18 scalar). */
    public void setLastTickWorkMs(long ms) {
        lastTickWorkMs.set(ms);
        tickDrift.record(ms);
    }

    // ── Legacy scalar setters (back-compat — kept for setMaintenance/setLastTickWorkMs callers) ──

    /**
     * @deprecated Replaced by per-bucket {@link #incActiveBucket}/{@link #decActiveBucket}.
     *             Retained as no-op back-compat for callers updating from Phase 17.
     *             Remove after Phase 18 wave completion.
     */
    @Deprecated
    public void setActiveEntities(int n) {
        // No-op: per-bucket gauges replace the scalar gauge.
    }

    /**
     * @deprecated Replaced by per-bucket {@link #incStalledBucket}/{@link #decStalledBucket}.
     *             Retained as no-op back-compat. Remove after Phase 18 wave completion.
     */
    @Deprecated
    public void setStalledSessions(int n) {
        // No-op: per-bucket gauges replace the scalar gauge.
    }

    // ── Scalar counters ──────────────────────────────────────────────────────

    /** Phase 19 SCALE-06 (REVIEWS LOW-12): increment when placement loses the trySetEntity race. */
    public void incLostRace()         { lostRace.increment(); }

    /** Increment when a STALLED session reconnects and rebinds its entity. */
    public void incRebound()          { rebound.increment(); }

    /** Increment when a STALLED session's grace window expires before reconnect. */
    public void incTerminalDropout()  { terminalDropouts.increment(); }

    /** Increment when a session transitions into STALLED (SLI denominator). */
    public void incStalledTotal()     { stalledTotal.increment(); }

    /**
     * Record an outbound encoded frame size in bytes.
     * Called by {@code OutboundSender.drainLoop}.
     */
    public void recordFrameSize(int bytes) { frameSize.record(bytes); }

    /** Phase 19.1 D-14: increment when drain VT join timed out in detachSession(String). */
    public void incDetachTimeout() { detachTimeout.increment(); }

    /**
     * Phase 20-01c (F2 remediation): register the aggregate peak-queue-depth gauge.
     *
     * <p>{@code peakSupplier} is invoked by Micrometer on every scrape and must return the
     * current max queue depth across all attached sessions. {@link OutboundSender} wires this
     * during its own construction so the gauge sees the live per-session queue map.
     *
     * <p>Idempotent at the registry layer: Micrometer dedupes by name + tags. Callers
     * registering twice (e.g. test double-injection) get one gauge.
     */
    public void registerOutboundQueueDepthMaxGauge(java.util.function.IntSupplier peakSupplier) {
        if (this.outboundQueueDepthSupplier != null) {
            log.warn("registerOutboundQueueDepthMaxGauge called twice; ignoring (first supplier wins)");
            return;
        }
        this.outboundQueueDepthSupplier = peakSupplier;
        Gauge.builder(M_OUTBOUND_QUEUE_DEPTH_MAX, this, m -> m.outboundQueueDepthSupplier.getAsInt())
                .description("Peak per-session outbound queue depth across all attached sessions")
                .register(registry);
    }

    /**
     * Phase 20-01c (F2 remediation): accessor for the encode+send Timer.
     * Used by {@link OutboundSender#drainLoop} to bracket the encode + sendMessage pair.
     */
    public Timer encodeSendTimer() { return encodeSendTimer; }
}
