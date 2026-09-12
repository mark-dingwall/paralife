package com.paralife.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paralife.admission.AdmissionGate;
import com.paralife.admission.AdmissionMetrics;
import com.paralife.admission.OutboundSender;
import com.paralife.admission.ResumeTokenRegistry;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.EnvCleanupHooksBean;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.LiveEntityRegistry;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Phase 19.1 D-09 — BL disconnect lifecycle leak assertions.
 *
 * <p>Verifies that after a bot disconnect (via {@code cleanupBot} or
 * {@code cleanupByEntityId}), all four env-side maps are empty for the
 * disconnected entity:
 * <ol>
 *   <li>{@link BuffRegistry} — no orphan buff entries</li>
 *   <li>infection map ({@link EnvCleanupHooksBean#getInfections()}) — no orphan infection</li>
 *   <li>FLEEING map ({@link EnvironmentEngine#getFleeing}) — no orphan FLEEING</li>
 *   <li>{@link AdmissionMetrics} bucket-tags map — no orphan snapshot</li>
 * </ol>
 *
 * <p>B4.1 note: the pre-fix baseline probe was performed inline (force-enabled via
 * {@code -Djunit.jupiter.conditions.deactivate}) and confirmed that BuffRegistry leaked
 * after cleanupBot on the pre-fix tree (unregisterEntity was never called). The single
 * commit that ships the fix also ships the enabled assertion tests below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WorldWebSocketHandlerCleanupTest {

    @Autowired WorldWebSocketHandler handler;
    @Autowired BuffRegistry buffRegistry;
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired EnvCleanupHooksBean envCleanupHooksBean;
    @Autowired AdmissionMetrics admissionMetrics;
    @Autowired OutboundSender outboundSender;
    @Autowired AdmissionGate admissionGate;
    @Autowired ResumeTokenRegistry resumeTokens;
    @Autowired BotRegistry bots;
    @Autowired LiveEntityRegistry liveEntities;
    @Autowired WorldGrid grid;
    @Autowired SessionRegistry sessions;

    private WebSocketSession session;
    private WebSocketSession collateral;
    private String entityId;
    private String token;
    private Position position;
    private int slotsBefore;
    private int activeBefore;

    @BeforeEach
    void setUp() throws Exception {
        slotsBefore = admissionGate.reservedSlots();
        activeBefore = admissionMetrics.totalActiveBucketCount();
        // Keep another real reservation alive: a double release must not hide behind the zero floor.
        collateral = openSession("cleanup-collateral", "operator", null);
        handler.handleMessage(collateral, new TextMessage("r|C"));
        assertThat(collateral.getAttributes()).containsKey("entityId");
        session = openSession("cleanup-test-session", "unit-test", null);
        handler.handleMessage(session, new TextMessage("r|C"));
        // Grab the entity id that was assigned after registration.
        Object eid = session.getAttributes().get("entityId");
        entityId = eid instanceof String s ? s : null;
        assertThat(entityId).as("entity must be registered after r|C").isNotNull();
        token = (String) session.getAttributes().get("resumeToken");
        position = bots.getBySession(session.getId()).orElseThrow().position();
    }

    @AfterEach
    void tearDown() throws Exception {
        handler.cleanupByEntityId(entityId);
        sessions.unregister(session.getId());
        outboundSender.detachSession(session.getId());
        handler.afterConnectionClosed(collateral, CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("cleanupBot: BuffRegistry, infection, FLEEING, and bucket-tags all empty post-disconnect")
    void cleanupBot_clearsAllEnvMaps() throws Exception {
        populateEnvState(entityId);

        // Pre-condition: at least the buff is present.
        assertThat(buffRegistry.getRegisteredEntityIds())
                .as("buff must be present before cleanup")
                .contains(entityId);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertAllMapsEmpty(entityId);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertAllMapsEmpty(entityId);
    }

    @Test
    @DisplayName("cleanupByEntityId: BuffRegistry, infection, FLEEING, and bucket-tags all empty post-cleanup")
    void cleanupByEntityId_clearsAllEnvMaps() {
        populateEnvState(entityId);

        assertThat(buffRegistry.getRegisteredEntityIds())
                .as("buff must be present before cleanup")
                .contains(entityId);

        handler.cleanupByEntityId(entityId);

        assertAllMapsEmpty(entityId);
        handler.cleanupByEntityId(entityId);
        assertAllMapsEmpty(entityId);
    }

    @Test
    @DisplayName("A4.2 idempotency: second cleanupBot call is a no-op; no double-decrement on admissionMetrics")
    void cleanupBot_idempotent_noDoubleDecrement() {
        populateEnvState(entityId);

        handler.cleanupBot(session);
        assertAllMapsEmpty(entityId);
        int activeAfterFirst = admissionMetrics.totalActiveBucketCount();

        // Second call on the same (now-cleaned) session — ATTR_ENTITY_TYPE already removed,
        // so wasRegistered=false: no slot release, no bucket dec.
        handler.cleanupBot(session);
        assertAllMapsEmpty(entityId);

        // Active count must not go below what the first call produced.
        assertThat(admissionMetrics.totalActiveBucketCount())
                .as("second cleanupBot must not double-decrement active bucket")
                .isEqualTo(activeAfterFirst);
        assertThat(admissionMetrics.minActiveBucketCount())
                .as("no bucket must go negative")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("A4.3 entityId-only path: cleanupByEntityId with no session clears env maps")
    void cleanupByEntityId_noSession_clearsEnvMaps() {
        // Populate env state BEFORE detaching the session from the registry.
        populateEnvState(entityId);

        sessions.unregister(session.getId());
        assertThat(sessions.getSession(session.getId())).isNull();
        assertThat(bots.getBySession(session.getId())).isPresent();

        // Directly call cleanupByEntityId without having a live session bound.
        // (cleanupBot was NOT called first — entity still has state.)
        handler.cleanupByEntityId(entityId);

        assertAllMapsEmpty(entityId);
        handler.cleanupByEntityId(entityId);
        assertAllMapsEmpty(entityId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void populateEnvState(String eid) {
        // Buff
        buffRegistry.grant(eid, BuffRegistry.BuffType.ATTACK_PLUS_1, 9999L);
        // Infection (direct map access — test-seam)
        envCleanupHooksBean.getInfections().put(eid,
                new com.paralife.engine.Infection(5, (byte) 1, 1, 5,
                        new com.paralife.world.Position(1, 1)));
        // FLEEING (package-visible test helper)
        environmentEngine.grantFleeingForTest(eid, 9999L, 5, 5);
        assertThat(buffRegistry.getRegisteredEntityIds()).contains(eid);
        assertThat(envCleanupHooksBean.getInfections()).containsKey(eid);
        assertThat(environmentEngine.getFleeing(eid)).isNotNull();
        assertThat(admissionMetrics.lookupBucketTags(eid)).isNotNull();
        assertThat(grid.getCell(position.x(), position.y()).occupant().id()).isEqualTo(eid);
        assertThat(bots.getBySession(session.getId()).orElseThrow().entityId()).isEqualTo(eid);
        assertThat(liveEntities.snapshot()).anyMatch(entry -> entry.entityId().equals(eid));
        assertThat(session.getAttributes()).containsEntry("entityId", eid).containsEntry("entityType", 'C');
        assertThat(tokenExists()).isTrue();
        assertThat(admissionGate.reservedSlots()).isEqualTo(slotsBefore + 2);
        assertThat(admissionMetrics.totalActiveBucketCount()).isEqualTo(activeBefore + 2);
    }

    private void assertAllMapsEmpty(String eid) {
        assertThat(buffRegistry.getRegisteredEntityIds())
                .as("BuffRegistry must not contain entry for %s after cleanup", eid)
                .doesNotContain(eid);
        assertThat(envCleanupHooksBean.getInfections())
                .as("infection map must not contain entry for %s after cleanup", eid)
                .doesNotContainKey(eid);
        assertThat(environmentEngine.getFleeing(eid))
                .as("FLEEING map must not contain entry for %s after cleanup", eid)
                .isNull();
        assertThat(grid.getCell(position.x(), position.y()).isEmpty()).isTrue();
        assertThat(bots.getBySession(session.getId())).isEmpty();
        assertThat(bots.getSessionByEntity(eid)).isEmpty();
        assertThat(liveEntities.snapshot()).noneMatch(entry -> entry.entityId().equals(eid));
        assertThat(tokenExists()).isFalse();
        assertThat(admissionGate.reservedSlots()).isEqualTo(slotsBefore + 1);
        assertThat(admissionMetrics.totalActiveBucketCount()).isEqualTo(activeBefore + 1);
        assertThat(bots.getBySession(collateral.getId())).isPresent();
        assertThat(admissionMetrics.lookupBucketTags(eid))
                .as("AdmissionMetrics bucket-tags must not contain entry for %s after cleanup", eid)
                .isNull();
    }

    private boolean tokenExists() {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(resumeTokens, "contains", token));
    }

    private WebSocketSession openSession(String id, String source, String harness) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(s.getAttributes()).thenReturn(attrs);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);

        HttpHeaders headers = new HttpHeaders();
        if (source != null) headers.add("X-Paralife-Source", source);
        if (harness != null) headers.add("X-Paralife-Harness", harness);
        when(s.getHandshakeHeaders()).thenReturn(headers);

        outboundSender.attachSession(s, 16);
        handler.afterConnectionEstablished(s);
        return s;
    }
}
