package com.paralife.websocket;

import com.paralife.admission.AdmissionMetrics;
import com.paralife.admission.OutboundSender;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.EnvCleanupHooksBean;
import com.paralife.engine.EnvironmentEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private WebSocketSession session;
    private String entityId;

    @BeforeEach
    void setUp() throws Exception {
        session = openSession("cleanup-test-session", "unit-test", null);
        handler.handleMessage(session, new TextMessage("r|C"));
        // Grab the entity id that was assigned after registration.
        Object eid = session.getAttributes().get("entityId");
        entityId = eid instanceof String s ? s : null;
        assertThat(entityId).as("entity must be registered after r|C").isNotNull();
    }

    @AfterEach
    void tearDown() {
        // Best-effort cleanup — the test may have already disconnected the session.
        try { outboundSender.detachSession("cleanup-test-session"); } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("cleanupBot: BuffRegistry, infection, FLEEING, and bucket-tags all empty post-disconnect")
    void cleanupBot_clearsAllEnvMaps() {
        populateEnvState(entityId);

        // Pre-condition: at least the buff is present.
        assertThat(buffRegistry.getRegisteredEntityIds())
                .as("buff must be present before cleanup")
                .contains(entityId);

        handler.cleanupBot(session);

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
    }

    @Test
    @DisplayName("A4.2 idempotency: second cleanupBot call is a no-op; no double-decrement on admissionMetrics")
    void cleanupBot_idempotent_noDoubleDecrement() {
        populateEnvState(entityId);

        int activeBefore = admissionMetrics.totalActiveBucketCount();

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

        // Directly call cleanupByEntityId without having a live session bound.
        // (cleanupBot was NOT called first — entity still has state.)
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
        assertThat(admissionMetrics.lookupBucketTags(eid))
                .as("AdmissionMetrics bucket-tags must not contain entry for %s after cleanup", eid)
                .isNull();
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
