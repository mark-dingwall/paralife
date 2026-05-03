package com.paralife.admission;

import com.paralife.engine.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Resume-token grace registry (Phase 17 D-12, D-13) — two-state lifecycle.
 *
 * <p>Each entry is in one of two states:
 * <ul>
 *   <li><b>ACTIVE</b>: token belongs to a connected, alive session. {@code expiresAtTick = Long.MAX_VALUE};
 *       sweep never reaps. Gauge does NOT count these.</li>
 *   <li><b>STALLED</b>: token is grace-held following queue overflow. {@code expiresAtTick} is set;
 *       sweep reaps when {@code currentTick >= expiresAtTick} (boundary inclusive). Gauge counts these.</li>
 * </ul>
 *
 * <p>Lifecycle (driven by Plan 07 {@code WorldWebSocketHandler}):
 * <ol>
 *   <li>Successful register → {@link #issueActive} → ACTIVE entry stored; gauge unchanged.</li>
 *   <li>Normal disconnect → {@link #clearActive} (removes ACTIVE entry; idempotent).</li>
 *   <li>Queue overflow → {@code WorldWebSocketHandler.markStalled} → {@link #convertToStalled}
 *       (ACTIVE → STALLED, expiry set, gauge increments).</li>
 *   <li>Reconnect with token → {@link #tryRebind} (STALLED consumed, fresh ACTIVE minted, gauge decrements).</li>
 *   <li>Grace expiry → sweep invokes the {@link #setCleanupCallback cleanup callback} with the ENTITY-ID.</li>
 * </ol>
 *
 * <p><b>Token format:</b> {@code r:%016x} (18 chars). The {@code r:} prefix is the Plan 02 codec
 * disambiguator — it MUST NOT be changed without updating {@code PerceptionCodec}.
 *
 * <p><b>Gauge:</b> {@code paralife.backpressure.stalled.sessions} counts STALLED entries only.
 * ACTIVE entries are excluded, fixing the gauge over-reporting bug from codex/opencode HIGH reviews.
 *
 * <p><b>Callback contract:</b> the {@link Consumer} registered via {@link #setCleanupCallback} receives
 * the ENTITY-ID (not the sessionId). Plan 07 wires this to {@code cleanupByEntityId(String)}, which
 * resolves entity → session via {@code BotRegistry.entityToSession} and performs cleanup.
 */
@Component
public class ResumeTokenRegistry {

    private static final Logger log = LoggerFactory.getLogger(ResumeTokenRegistry.class);

    private final AdmissionConfig admissionConfig;
    private final AdmissionMetrics metrics;
    private final ConcurrentHashMap<String, ResumeEntry> tokenMap = new ConcurrentHashMap<>();
    private final AtomicInteger stalledCount = new AtomicInteger();

    /**
     * Cleanup callback: receives ENTITY-ID on grace expiry.
     * Set at startup by Plan 07 {@code WorldWebSocketHandler} via {@link #setCleanupCallback}.
     */
    private volatile Consumer<String> cleanupCallback;

    public ResumeTokenRegistry(AdmissionConfig admissionConfig, AdmissionMetrics metrics) {
        this.admissionConfig = admissionConfig;
        this.metrics = metrics;
    }

    /**
     * Register a cleanup callback that receives ENTITY-ID when a grace window expires.
     * Plan 07 wires this to {@code WorldWebSocketHandler::cleanupByEntityId}.
     */
    public void setCleanupCallback(Consumer<String> entityIdConsumer) {
        this.cleanupCallback = entityIdConsumer;
    }

    /**
     * Mint a fresh ACTIVE token for {@code entityId} owned by {@code sessionId}.
     * The gauge is UNCHANGED — ACTIVE entries do not count as stalled sessions.
     *
     * <p>Collision-safe: loops on the (astronomically rare) 64-bit token collision via
     * {@code putIfAbsent} until a unique token slot is acquired.
     *
     * @param entityId  the BotRegistry entity identifier
     * @param sessionId the WebSocket session identifier
     * @return a fresh token in {@code r:%016x} format
     */
    public String issueActive(String entityId, String sessionId) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(sessionId, "sessionId");
        ResumeEntry entry = new ResumeEntry(entityId, sessionId, Long.MAX_VALUE, State.ACTIVE);
        String token;
        do {
            token = generateToken();
        } while (tokenMap.putIfAbsent(token, entry) != null);
        return token;
    }

    /**
     * Remove any ACTIVE entry for {@code entityId}. Idempotent.
     * Called by Plan 07 on normal session close (NOT stall — that uses {@link #convertToStalled}).
     * STALLED entries for the same entity are preserved; their expiry drives cleanup.
     */
    public void clearActive(String entityId) {
        if (entityId == null) return;
        Iterator<Map.Entry<String, ResumeEntry>> it = tokenMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ResumeEntry> e = it.next();
            if (e.getValue().state == State.ACTIVE && e.getValue().entityId.equals(entityId)) {
                it.remove();
            }
        }
    }

    /**
     * Transition an ACTIVE entry to STALLED. Sets {@code expiresAtTick = currentTick + graceWindowTicks}.
     * Increments the stalled-sessions gauge. If the token is already STALLED, logs a warning and returns
     * the token unchanged (idempotent — gauge not double-counted). If the token is unknown, logs a warning
     * and returns the token unchanged.
     *
     * @param token       the resume token to transition
     * @param currentTick the tick number at which stalling occurs
     * @return the token (unchanged)
     */
    public String convertToStalled(String token, long currentTick) {
        if (token == null) return null;
        long graceTicks = admissionConfig.backpressure().graceWindowTicks();
        boolean[] transitioned = {false};
        tokenMap.compute(token, (k, existing) -> {
            if (existing == null) {
                log.warn("convertToStalled: unknown token={} (no-op)", token);
                return null;
            }
            if (existing.state == State.STALLED) {
                log.warn("convertToStalled: token={} already STALLED (no-op)", token);
                return existing;
            }
            transitioned[0] = true;
            return new ResumeEntry(existing.entityId, existing.originalSessionId,
                    currentTick + graceTicks, State.STALLED);
        });
        if (transitioned[0]) {
            int n = stalledCount.incrementAndGet();
            metrics.setStalledSessions(n);
        }
        return token;
    }

    /**
     * Attempt to consume a STALLED {@code token} for re-bind. Returns empty if:
     * <ul>
     *   <li>token is null or unknown</li>
     *   <li>token state == ACTIVE (a live session's armed token cannot be rebound — T-17-live-rebind)</li>
     *   <li>token is expired ({@code expiresAtTick <= currentTick})</li>
     * </ul>
     * On success: atomically removes the STALLED entry (single-use, prevents replay — T-17-01),
     * mints a fresh ACTIVE token for the same entity, decrements the stalled gauge.
     *
     * @param token      the resume token supplied by the reconnecting client
     * @param newSessionId the new WebSocket session identifier
     * @param currentTick the current tick number
     * @return {@link RebindOutcome} on success, empty on failure
     */
    public Optional<RebindOutcome> tryRebind(String token, String newSessionId, long currentTick) {
        if (token == null) return Optional.empty();
        ResumeEntry old = tokenMap.get(token);
        if (old == null || old.state != State.STALLED) return Optional.empty();
        if (old.expiresAtTick <= currentTick) {
            // Edge case: expired but not yet swept by the tick handler.
            return Optional.empty();
        }
        // Atomic single-use: compare-and-remove prevents concurrent re-bind races.
        if (!tokenMap.remove(token, old)) {
            return Optional.empty();   // raced with sweep or another rebind
        }
        int n = stalledCount.decrementAndGet();
        metrics.setStalledSessions(n);
        String freshToken = issueActive(old.entityId, newSessionId);
        return Optional.of(new RebindOutcome(old.entityId, freshToken));
    }

    /**
     * Phase 19.5 H-C — rewrite every entry whose {@code entityId} equals
     * {@code oldEntityId} to use {@code newEntityId} instead. Fired by
     * {@link com.paralife.engine.EntityLifecycleListener#onEntityRemapped}
     * on bond formation, composite formation, revert, and dissolve.
     *
     * <p>Without this, a STALLED grace-token issued before bond formation would
     * still resolve to the pre-bond particle id at reconnect, leaking the
     * BondedPair until the grace window expired.
     *
     * <p>Both ACTIVE and STALLED entries are rewritten in place — token map
     * key (the resume token) is unchanged; only the {@code entityId} field of
     * the {@link ResumeEntry} value is updated. Idempotent: a no-op when no
     * entries match, and safe to call when {@code oldEntityId == newEntityId}.
     */
    public void remapEntity(String oldEntityId, String newEntityId) {
        if (oldEntityId == null || newEntityId == null) return;
        if (oldEntityId.equals(newEntityId)) return;
        for (Map.Entry<String, ResumeEntry> e : tokenMap.entrySet()) {
            ResumeEntry old = e.getValue();
            if (old.entityId.equals(oldEntityId)) {
                ResumeEntry updated = new ResumeEntry(
                        newEntityId, old.originalSessionId, old.expiresAtTick, old.state);
                tokenMap.replace(e.getKey(), old, updated);
            }
        }
    }

    /**
     * Tick-driven expiry sweep. Reaps entries where {@code state == STALLED AND expiresAtTick <= currentTick}
     * (boundary inclusive: a token set to expire at tick 105 is reaped exactly at tick 105).
     * ACTIVE entries are never touched by this method.
     *
     * <p>Runs at {@code @Order(1)} — before {@code SimulationEngine @Order(10)} — so that dead entities
     * are removed from the grid before the simulation tick processes them.
     */
    @EventListener
    @Order(1)
    public void onTick(TickEvent event) {
        long currentTick = event.tickNumber();
        Consumer<String> cleanup = this.cleanupCallback;
        int reaped = 0;
        Iterator<Map.Entry<String, ResumeEntry>> it = tokenMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ResumeEntry> e = it.next();
            ResumeEntry entry = e.getValue();
            if (entry.state == State.STALLED && entry.expiresAtTick <= currentTick) {
                // Atomic compare-and-remove FIRST: if a concurrent tryRebind already consumed
                // this entry, we must NOT run cleanup — the rebound session owns the entity now.
                if (!tokenMap.remove(e.getKey(), entry)) {
                    continue;
                }
                String entityId = entry.entityId;
                if (cleanup != null) {
                    try {
                        cleanup.accept(entityId);
                    } catch (RuntimeException ex) {
                        log.warn("Cleanup callback failed for entity={}: {}", entityId, ex.getMessage());
                    }
                } else {
                    log.warn("BACKPRESSURE expired tick={} entity={} — no cleanup callback wired",
                            currentTick, entityId);
                }
                log.info("BACKPRESSURE expired tick={} entity={} session={}",
                        currentTick, entityId, entry.originalSessionId);
                reaped++;
            }
        }
        if (reaped > 0) {
            int n = stalledCount.addAndGet(-reaped);
            metrics.setStalledSessions(n);
        }
    }

    private static String generateToken() {
        return String.format("r:%016x", ThreadLocalRandom.current().nextLong());
    }

    // ---- Inner types ----

    /** Two-state lifecycle for registry entries. */
    public enum State { ACTIVE, STALLED }

    /**
     * Internal stored value. Records are immutable; state transitions allocate new instances.
     * Package-private accessor {@link #peek} exposes this for tests.
     */
    public record ResumeEntry(String entityId, String originalSessionId, long expiresAtTick, State state) {}

    /**
     * Result of a successful STALLED-token rebind.
     *
     * @param entityId        the entity that was re-bound
     * @param freshResumeToken the newly minted ACTIVE resume token (in {@code r:%016x} format)
     */
    public record RebindOutcome(String entityId, String freshResumeToken) {}

    // ---- Test-only accessors (package-private) ----

    /** Total number of entries (ACTIVE + STALLED). */
    int size() { return tokenMap.size(); }

    /** Number of STALLED entries (matches gauge value). */
    int stalledSize() { return stalledCount.get(); }

    /** Whether the map contains the given token key. */
    boolean contains(String token) { return tokenMap.containsKey(token); }

    /** Peek at the raw entry for a token (without consuming it). */
    Optional<ResumeEntry> peek(String token) { return Optional.ofNullable(tokenMap.get(token)); }
}
