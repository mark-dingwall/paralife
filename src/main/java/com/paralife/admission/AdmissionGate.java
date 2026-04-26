package com.paralife.admission;

import com.paralife.websocket.RespawnConfig;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Admission decision point for Phase 17 (D-01..D-08, D-13..D-16).
 *
 * <p>This bean is the single authority for all bot registration and respawn admission
 * decisions. {@link com.paralife.websocket.WorldWebSocketHandler} delegates to
 * {@link #evaluate(AdmissionRequest)} on every {@code r|} frame (wired in Plan 07).
 *
 * <p>Guard order (corrected per codex MEDIUM review — already-registered runs BEFORE
 * resume-token to prevent live-session confused-deputy attack vector T-17-confused):
 * <ol>
 *   <li>Maintenance flag (D-16) → {@code 429 maintenance}</li>
 *   <li>Tick-health hysteresis gate (D-14) → {@code 429 tick-overload}</li>
 *   <li>Already-registered (FSM Alive) → {@code 409 already-registered}.
 *       A live session re-sending {@code r|<type>|<token>} is client confusion — return 409
 *       rather than silently swap entities via rebind ({@code tryRebind} NOT called).</li>
 *   <li>Resume-token re-bind (D-13) → preserved entity, fresh token ({@link AdmissionResult.Rebind}).</li>
 *   <li>Global cap (D-01) → {@code 429 world-full}</li>
 *   <li>Per-session respawn cap → {@code 429 respawn-cap} (only when {@code isRespawn=true})</li>
 *   <li>All guards passed → {@link AdmissionResult.Allow#INSTANCE}</li>
 * </ol>
 *
 * <p>Every rejection emits the {@code ADMISSION} log marker (D-19) and increments
 * {@link AdmissionMetrics#incRejected(String)} with the rejection token as tag value.
 */
@Component
public class AdmissionGate {

    private static final Logger log = LoggerFactory.getLogger(AdmissionGate.class);

    private final AdmissionConfig admissionConfig;
    private final RespawnConfig respawnConfig;
    private final WorldGrid worldGrid;
    private final TickHealthMonitor tickHealthMonitor;
    private final ResumeTokenRegistry resumeTokenRegistry;
    private final AdmissionMetrics metrics;

    public AdmissionGate(AdmissionConfig admissionConfig,
                         RespawnConfig respawnConfig,
                         WorldGrid worldGrid,
                         TickHealthMonitor tickHealthMonitor,
                         ResumeTokenRegistry resumeTokenRegistry,
                         AdmissionMetrics metrics) {
        this.admissionConfig = admissionConfig;
        this.respawnConfig = respawnConfig;
        this.worldGrid = worldGrid;
        this.tickHealthMonitor = tickHealthMonitor;
        this.resumeTokenRegistry = resumeTokenRegistry;
        this.metrics = metrics;
    }

    /**
     * Evaluate admission for a single bot registration or respawn attempt.
     *
     * @param req Request context assembled by the WebSocket handler.
     * @return {@link AdmissionResult.Allow} to proceed, {@link AdmissionResult.Reject}
     *         to send an error frame, or {@link AdmissionResult.Rebind} to re-attach
     *         the session to an existing entity.
     */
    public AdmissionResult evaluate(AdmissionRequest req) {

        // Guard 1: Maintenance (D-16). Checked first — operator intent takes absolute precedence.
        if (admissionConfig.maintenance()) {
            return reject(req, 429, RejectionToken.MAINTENANCE);
        }

        // Guard 2: Tick-overload (D-14). Hysteresis gate from TickHealthMonitor.
        if (tickHealthMonitor.isOverloaded()) {
            return reject(req, 429, RejectionToken.TICK_OVERLOAD);
        }

        // Guard 3: Already-registered — BEFORE resume-token (corrected per codex MEDIUM / T-17-confused).
        // A currently Alive session re-sending r|<type>|<token> is client confusion.
        // Return 409 deterministically; tryRebind is NOT called.
        if (req.alreadyAlive()) {
            return reject(req, 409, RejectionToken.ALREADY_REGISTERED);
        }

        // Guard 4: Resume-token re-bind (D-13). Valid STALLED token bypasses cap checks —
        // the entity is already counted in livingEntityCount().
        Optional<String> token = req.resumeToken();
        if (token.isPresent()) {
            Optional<ResumeTokenRegistry.RebindOutcome> rebind =
                    resumeTokenRegistry.tryRebind(token.get(), req.sessionId(), req.tickNumber());
            if (rebind.isPresent()) {
                ResumeTokenRegistry.RebindOutcome outcome = rebind.get();
                return new AdmissionResult.Rebind(outcome.entityId(), outcome.freshResumeToken());
            }
            // Unknown or expired token → fall through to fresh-registration path (D-13 back-compat).
        }

        // Guard 5: Global cap (D-01). World-full check after rebind so valid resume tokens bypass it.
        int active = worldGrid.livingEntityCount();
        if (active >= admissionConfig.cap()) {
            return reject(req, 429, RejectionToken.WORLD_FULL);
        }

        // Guard 6: Per-session respawn cap. Only applies to respawn frames, not initial registration.
        if (req.isRespawn() && req.respawnCount() >= respawnConfig.maxRespawnsPerSession()) {
            return reject(req, 429, RejectionToken.RESPAWN_CAP);
        }

        return AdmissionResult.Allow.INSTANCE;
    }

    /**
     * Emit metric + D-19 log marker, then return the Reject result.
     */
    private AdmissionResult.Reject reject(AdmissionRequest req, int code, String token) {
        metrics.incRejected(token);
        log.info("ADMISSION rejected tick={} session={} reason={} active={}/{}",
                req.tickNumber(), req.sessionId(), token,
                worldGrid.livingEntityCount(), admissionConfig.cap());
        return new AdmissionResult.Reject(code, token);
    }

    /**
     * Immutable context for a single admission evaluation.
     *
     * @param sessionId    Server-assigned WebSocket session ID (trusted).
     * @param tickNumber   Current simulation tick at admission time.
     * @param alreadyAlive True if this session is currently in FSM state Alive (has {@code entityId} attr).
     * @param isRespawn    True if this is a respawn {@code r|} frame (not the initial registration).
     * @param respawnCount Number of respawns already consumed by this session.
     * @param resumeToken  Optional resume token from the {@code r|<type>|<token>} third slot.
     */
    public record AdmissionRequest(
            String sessionId,
            long tickNumber,
            boolean alreadyAlive,
            boolean isRespawn,
            int respawnCount,
            Optional<String> resumeToken) {

        public AdmissionRequest {
            if (sessionId == null) throw new IllegalArgumentException("sessionId required");
            if (resumeToken == null) resumeToken = Optional.empty();
        }
    }
}
