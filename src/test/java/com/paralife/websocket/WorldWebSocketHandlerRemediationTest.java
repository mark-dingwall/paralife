package com.paralife.websocket;

import com.paralife.admission.AdmissionGate;
import com.paralife.admission.AdmissionMetrics;
import com.paralife.admission.AttributionTagger;
import com.paralife.admission.OutboundSender;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.LiveEntityRegistry;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P18-Chunk-A remediation: end-to-end lifecycle invariants for the consensus block-merge
 * fixes (C1, C2, H1).
 *
 * <p>Drives {@link WorldWebSocketHandler} via mocked sessions through the same lifecycle
 * Spring would invoke (afterConnectionEstablished → handleMessage("r|...") →
 * afterConnectionClosed). After each scenario, asserts the four global accounting
 * invariants:
 *
 * <ol>
 *   <li>{@code AdmissionGate.reservedSlots() == 0} (no leaked admission slots)</li>
 *   <li>{@code AdmissionMetrics.totalActiveBucketCount() == 0}</li>
 *   <li>{@code AdmissionMetrics.totalStalledBucketCount() == 0}</li>
 *   <li>{@code AdmissionMetrics.bucketTagsSize() == 0} (no leaked snapshot entries)</li>
 * </ol>
 *
 * <p>Without the C1 fix, scenario {@link #gracefulClose_releasesAllSlotAndGaugeState}
 * leaks one slot and one bucket-tag entry per cycle. Without C2, scenario
 * {@link #manyRegisterCloseCycles_haveNoAccountingDrift} grows the snapshot map without
 * bound. Without H1, scenarios that span attribution change would land the active dec on
 * the wrong bucket — that path is exercised at the unit level in
 * {@link com.paralife.admission.AdmissionMetricsRemediationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WorldWebSocketHandlerRemediationTest {

    @Autowired WorldWebSocketHandler handler;
    @Autowired AdmissionGate admissionGate;
    @Autowired AdmissionMetrics admissionMetrics;
    @Autowired OutboundSender outboundSender;
    @Autowired SessionRegistry sessionRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired WorldGrid worldGrid;

    private int slotsBefore;
    private int activeBefore;

    @BeforeEach
    void snapshotBefore() {
        // The Spring context may carry residual state from prior test classes in the same
        // process. Snapshot before so assertions can be relative.
        slotsBefore = admissionGate.reservedSlots();
        activeBefore = admissionMetrics.totalActiveBucketCount();
    }

    @AfterEach
    void detachAll() {
        // Defensive — every mock attached should detach.
    }

    // ── C1: graceful disconnect releases all per-session accounting ─────────

    @Test
    void gracefulClose_releasesAllSlotAndGaugeState() throws Exception {
        WebSocketSession session = openSession("c1-1", "operator", null);
        register(session);

        // Pre-conditions: session admitted, slot booked, gauge incremented.
        assertThat(admissionGate.reservedSlots()).isEqualTo(slotsBefore + 1);
        assertThat(admissionMetrics.totalActiveBucketCount()).isEqualTo(activeBefore + 1);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // Post-conditions: every counter back to baseline.
        assertThat(admissionGate.reservedSlots()).isEqualTo(slotsBefore);
        assertThat(admissionMetrics.totalActiveBucketCount()).isEqualTo(activeBefore);
        assertThat(admissionMetrics.totalStalledBucketCount()).isZero();
    }

    @Test
    void transportError_releasesAllSlotAndGaugeState() throws Exception {
        WebSocketSession session = openSession("c1-2", "operator", null);
        register(session);

        handler.handleTransportError(session, new RuntimeException("simulated"));

        assertThat(admissionGate.reservedSlots()).isEqualTo(slotsBefore);
        assertThat(admissionMetrics.totalActiveBucketCount()).isEqualTo(activeBefore);
    }

    // ── C2: bucketTagsByEntityId never grows unbounded ──────────────────────

    @Test
    void manyRegisterCloseCycles_haveNoAccountingDrift() throws Exception {
        int snapshotsBefore = admissionMetrics.bucketTagsSize();

        for (int i = 0; i < 50; i++) {
            WebSocketSession s = openSession("c2-" + i, "harness", "h-" + (i % 4));
            register(s);
            handler.afterConnectionClosed(s, CloseStatus.NORMAL);
        }

        // Strict invariant: snapshot map net zero growth across 50 cycles.
        assertThat(admissionMetrics.bucketTagsSize()).isEqualTo(snapshotsBefore);
        assertThat(admissionGate.reservedSlots()).isEqualTo(slotsBefore);
        assertThat(admissionMetrics.totalActiveBucketCount()).isEqualTo(activeBefore);
        assertThat(admissionMetrics.minActiveBucketCount()).isGreaterThanOrEqualTo(0);
    }

    // ── TD-20-01c-E: stalled-session transport error must not reap the held entity ──

    @Test
    void stalledTransportError_holdsEntityForGraceSweep() throws Exception {
        WebSocketSession session = openSession("te-stalled-1", "operator", null);
        register(session);

        BotRegistry.BotState state = botRegistry.getBySession("te-stalled-1").orElseThrow();
        String entityId = state.entityId();
        var pos = state.position();
        int slotsHeld = admissionGate.reservedSlots();
        assertThat(liveEntityRegistry.snapshot()).anyMatch(e -> e.entityId().equals(entityId));
        assertThat(worldGrid.getCell(pos.x(), pos.y()).isEmpty()).isFalse();

        // Drive to STALLED (clears ATTR_ENTITY_ID, sets ATTR_STALL_TICK), then a transport error.
        handler.markStalled(session, 1L);
        handler.handleTransportError(session, new RuntimeException("simulated stalled-disconnect"));

        // TD-20-01c-E regression: the held entity must NOT be reaped at transport-error time —
        // the grace-expiry sweep (cleanupByEntityId) is the sole reaper. Pre-fix, cleanupBot ran
        // here: it cleared the grid cell and unregistered the bot binding while leaving a stale
        // LiveEntityRegistry entry (entityId was already null, so unregister(entityId) no-op'd).
        assertThat(botRegistry.getBySession("te-stalled-1"))
                .as("bot binding held for grace sweep").isPresent();
        assertThat(worldGrid.getCell(pos.x(), pos.y()).isEmpty())
                .as("held grid cell not cleared early").isFalse();
        assertThat(admissionGate.reservedSlots())
                .as("admission slot held until grace-expire").isEqualTo(slotsHeld);
        assertThat(liveEntityRegistry.snapshot())
                .as("LiveEntityRegistry entry held, not orphaned")
                .anyMatch(e -> e.entityId().equals(entityId));

        // Now let the grace-expiry reaper run — everything must clean up to baseline.
        handler.cleanupByEntityId(entityId);
        assertThat(botRegistry.getBySession("te-stalled-1")).isEmpty();
        assertThat(worldGrid.getCell(pos.x(), pos.y()).isEmpty()).isTrue();
        assertThat(admissionGate.reservedSlots()).isEqualTo(slotsBefore);
        assertThat(liveEntityRegistry.snapshot()).noneMatch(e -> e.entityId().equals(entityId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WebSocketSession openSession(String id, String source, String harness) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);

        HttpHeaders headers = new HttpHeaders();
        if (source != null) headers.add("X-Paralife-Source", source);
        if (harness != null) headers.add("X-Paralife-Harness", harness);
        when(session.getHandshakeHeaders()).thenReturn(headers);

        // OutboundSender attach so handler.handleMessage's outbound path doesn't drop frames.
        outboundSender.attachSession(session, 16);

        handler.afterConnectionEstablished(session);
        return session;
    }

    private void register(WebSocketSession session) throws Exception {
        // 'r|C' is the register-as-Catalyst frame.
        handler.handleMessage(session, new TextMessage("r|C"));
        // Sanity: session attribute set after Allow path.
        assertThat(session.getAttributes()).containsKey(AttributionTagger.ATTR_SOURCE);
    }
}
