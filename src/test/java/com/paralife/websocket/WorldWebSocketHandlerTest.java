package com.paralife.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.paralife.admission.AdmissionConfig;
import com.paralife.admission.OutboundSender;
import com.paralife.admission.ResumeTokenRegistry;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.ActionResolver;
import com.paralife.engine.TickEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Plan 15-06 Task 3 — WorldWebSocketHandler FSM tests.
 *
 * <p>Exercises the two hinge behaviours of the Plan 15-06 handler rewrite:
 * malformed inbound text produces {@code E|400}, and the per-session respawn
 * cap (T-15-04) emits {@code E|429} when exceeded. Both behaviours are
 * encoded as outbound frames through {@link PerceptionCodec} — so the test
 * round-trips the captured send-message bytes back through the decoder to
 * assert on the structured frame rather than the raw string.
 *
 * <p>Uses {@code @SpringBootTest(webEnvironment=NONE)} to pick up real bean
 * wiring (including the {@link com.paralife.engine.AlarmQueue} bean new in
 * plan 15-06). {@code paralife.tick.auto-start=false} prevents the virtual
 * thread tick loop from starting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WorldWebSocketHandlerTest {

    @Autowired
    WorldWebSocketHandler handler;

    @Autowired
    OutboundSender outboundSender;

    @Autowired
    ResumeTokenRegistry resumeTokenRegistry;

    @Autowired
    AdmissionConfig admissionConfig;

    // A29 — spy the real bean so the "SHALL NOT queue" conjunct is isolable via verify(never()).
    @SpyBean
    ActionResolver actionResolver;

    private WebSocketSession session;
    private Map<String, Object> attrs;

    @BeforeEach
    void setUp() {
        session = mock(WebSocketSession.class);
        attrs = new HashMap<>();
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        // Phase 17: outbound now flows through OutboundSender's per-session VT drain loop.
        // Attach the mock so offer() succeeds and the drain loop calls session.sendMessage.
        outboundSender.attachSession(session, 16);
    }

    @AfterEach
    void tearDown() {
        outboundSender.detachSession("s1");
    }

    @Test
    void malformedFrameProducesError400() throws Exception {
        // "GARBAGE" starts with 'G' which the codec's frame-type switch rejects
        // as "Unknown frame type". Handler must wrap this in E|400.
        handler.handleMessage(session, new TextMessage("GARBAGE"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        // timeout() polls — VT drain loop sends asynchronously.
        verify(session, timeout(2000).atLeastOnce()).sendMessage(captor.capture());
        String out = captor.getValue().getPayload();
        // A30 — pin the full condition→token routing on the CodecException path, not just the code.
        assertEquals("E|400|malformed", out, "Expected exact malformed wire literal, got: " + out);
    }

    // ── A29/A30 — condition→token routing anchors (ADMISSION §0) ───────────────

    /** Wire strings for server→client-only frames a client must never send (both encode-derived → decodable). */
    static Stream<Arguments> clientIllegalFrames() {
        String syncWire = PerceptionCodec.encode(new Frame.SyncFrame("e1", Optional.empty(), List.of()));
        String tickWire = PerceptionCodec.encode(new Frame.TickFrame(
                0, 0, 0, 0, 0, 0, List.of(), Optional.empty(), List.of(), List.of(), Optional.empty(), List.of()));
        return Stream.of(
                Arguments.of("SyncFrame", syncWire, Frame.SyncFrame.class),
                Arguments.of("TickFrame", tickWire, Frame.TickFrame.class));
    }

    /**
     * A30 — a well-formed but client-illegal frame direction (Sync/Tick, server→client only) is
     * rejected as {@code E|400|malformed}. The instanceof precondition proves the wire genuinely
     * decodes to the illegal-direction frame (reaching that switch arm) rather than silently
     * falling back to the CodecException arm if a future codec change made S/T throw.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("clientIllegalFrames")
    void clientIllegalFrameDirectionRejectedAsMalformed(String label, String wire, Class<?> expectedType)
            throws Exception {
        assertInstanceOf(expectedType, PerceptionCodec.decode(wire),
                label + " wire must decode to the illegal-direction frame, not throw");

        handler.handleMessage(session, new TextMessage(wire));

        verify(session, timeout(2000).atLeastOnce()).sendMessage(argThat(
                msg -> msg instanceof TextMessage tm && tm.getPayload().equals("E|400|malformed")));
    }

    /**
     * A29 (reject conjunct + not-queued conjunct) — an action frame on a session with no active
     * entity (never registered) is rejected {@code E|404|no-active-entity} AND the action is never
     * queued. {@code @SpyBean ActionResolver} isolates the second SHALL.
     */
    @Test
    void actionOnUnregisteredSessionRejectedNoActiveEntity() throws Exception {
        // Valid move action (verb M requires a numpad-digit arg per §8.6) so it decodes to an
        // ActionFrame and reaches handleAction — an arg-less "a|M" would be rejected as malformed.
        String action = PerceptionCodec.encode(new Frame.ActionFrame('M', Optional.of("1")));

        handler.handleMessage(session, new TextMessage(action));

        verify(session, timeout(2000).atLeastOnce()).sendMessage(argThat(
                msg -> msg instanceof TextMessage tm && tm.getPayload().equals("E|404|no-active-entity")));
        // Second SHALL: the unregistered action must not reach the resolver.
        verify(actionResolver, never()).queueAction(eq("s1"), any());
    }

    /**
     * A29 positive control — the same harness DOES route an action to the resolver once the session
     * is registered, proving both the 404 and the not-queued conjuncts are condition-specific (not
     * "every action is dropped"). Registration first (r|C) places an entity, then a|M|1 queues.
     *
     * <p>Uses a distinct session id ("s2"): this class shares "s1" and {@code markDead} does not
     * unregister from the singleton {@code LiveEntityRegistry} in the shared context, so a second
     * fresh registration of entity-s1 would collide ("Conflicting re-register").
     */
    @Test
    void actionOnRegisteredSessionIsQueued() throws Exception {
        WebSocketSession registered = mock(WebSocketSession.class);
        when(registered.getAttributes()).thenReturn(new HashMap<>());
        when(registered.getId()).thenReturn("s2");
        when(registered.isOpen()).thenReturn(true);
        outboundSender.attachSession(registered, 16);
        try {
            handler.handleMessage(registered, new TextMessage("r|C"));
            // Assert registration actually placed an entity (S| sync) before relying on it — a
            // pressured admission/placement would otherwise surface as a confusing queueAction
            // timeout rather than a clear "registration failed".
            verify(registered, timeout(2000).atLeastOnce()).sendMessage(argThat(
                    msg -> msg instanceof TextMessage tm && tm.getPayload().startsWith("S|")));

            String action = PerceptionCodec.encode(new Frame.ActionFrame('M', Optional.of("1")));
            handler.handleMessage(registered, new TextMessage(action));

            verify(actionResolver, timeout(2000)).queueAction(eq("s2"), any());
        } finally {
            // Full unregister (LiveEntityRegistry + grid cell + BotRegistry + admission slot) so the
            // entity does not leak into the shared-context singletons — markDead alone would not.
            handler.cleanupBot(registered);
            outboundSender.detachSession("s2");
        }
    }

    // ── A14 — engine-direct twin: rebind restores the pre-stall respawn count ──

    /** Fresh mock session with the given id, attached to the outbound drain loop. */
    private WebSocketSession newAttachedSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        outboundSender.attachSession(s, 16);
        return s;
    }

    private static Frame decodeQuietly(String wire) {
        try {
            return PerceptionCodec.decode(wire);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A14 (respawn-count conjunct) — default-suite twin of the {@code @Tag("slow")}
     * {@code StallRecoveryIntegrationTest.respawnCountRestoredAcrossRebind}. {@code markStalled} is
     * public and {@code AdmissionGate} returns {@code Rebind} on a valid STALLED token, so the
     * register→respawn→stall→rebind flow drives fast with mock sessions — no overflow (the thing that
     * makes the {@code @slow} test slow). Pins the respawn-cap-bypass guard: a rebind restores the
     * pre-stall respawn count rather than resetting it to 0.
     *
     * <p>Distinct session ids ("sc1"/"sc2") avoid colliding with the class's shared "s1"/"s2"
     * registrations in the shared context ({@code markDead} does not unregister the base entity — the
     * same pre-existing limitation {@code respawnCapEnforced} exhibits).
     */
    @Test
    void rebindRestoresRespawnCountFromStallSnapshot() throws Exception {
        WebSocketSession sc1 = newAttachedSession("sc1");
        try {
            // Register, then markDead + re-register so the second registration is a RESPAWN
            // (ATTR_ENTITY_TYPE retained by markDead → respawnCount ticks to 1).
            handler.handleMessage(sc1, new TextMessage("r|C"));
            handler.markDead(sc1);
            handler.handleMessage(sc1, new TextMessage("r|C"));

            // Capture the respawn Sync's entityId + ACTIVE resume token (the token markStalled converts).
            ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
            verify(sc1, timeout(2000).atLeast(2)).sendMessage(cap.capture());
            Frame.SyncFrame respawnSync = cap.getAllValues().stream()
                    .map(m -> decodeQuietly(m.getPayload()))
                    .filter(f -> f instanceof Frame.SyncFrame)
                    .map(f -> (Frame.SyncFrame) f)
                    .reduce((first, second) -> second) // last Sync = the respawn
                    .orElseThrow();
            String entityId = respawnSync.entityId();
            String stalledToken = respawnSync.resumeToken().orElseThrow();

            // Stall: snapshots respawnCount(=1) into respawnCountAtStall and converts the ACTIVE token
            // → STALLED. markStalled is the public entry the backpressure path calls — no overflow.
            handler.markStalled(sc1, 0L);

            // Rebind on a fresh session within the grace window (currentTick stays 0, grace=10 default).
            WebSocketSession sc2 = newAttachedSession("sc2");
            try {
                handler.handleMessage(sc2, new TextMessage(
                        PerceptionCodec.encode(new Frame.RegisterFrame('C', Optional.of(stalledToken)))));

                // Non-vacuity, two parts. The S| Sync rules out the error / stale-resume-token
                // branch (L568-575 sends E|400); it does NOT by itself distinguish a Rebind from a
                // fresh-registration Allow (both emit S|). The preserved entityId is the real
                // discriminator — a fresh registration would mint a NEW entityId, so this asserts the
                // Rebind path specifically ran, making the respawnCount assertion non-vacuous.
                verify(sc2, timeout(2000).atLeastOnce()).sendMessage(argThat(
                        msg -> msg instanceof TextMessage tm && tm.getPayload().startsWith("S|")));
                assertEquals(entityId, sc2.getAttributes().get("entityId"),
                        "rebind must preserve the entityId onto the new session (not a fresh registration)");

                // A14 respawn-count restore: the new session carries the pre-stall count, not a reset 0.
                assertEquals(Integer.valueOf(1), sc2.getAttributes().get("respawnCount"),
                        "rebind must restore the pre-stall respawnCount (respawn-cap-bypass guard)");
            } finally {
                handler.cleanupBot(sc2);
                outboundSender.detachSession("sc2");
            }
        } finally {
            handler.cleanupBot(sc1);
            outboundSender.detachSession("sc1");
        }
    }

    /**
     * A32 (routing) — the last handler-emitted §1 token's condition→token routing pin, closing the
     * EARS §0 sweep. WHEN any inbound frame arrives on a STALLED session THE SYSTEM SHALL reject it
     * {@code E|408|reconnect-required} (best-effort per D-07 — the send is {@code isOpen()}-gated; the
     * mock stays open, so it is observable here) and close the transport ({@code SERVICE_RESTARTED}),
     * short-circuiting the {@link WorldWebSocketHandler#handleTextMessage} guard (~:474) before the
     * frame reaches the decode/dispatch switch.
     *
     * <p>This is the <b>sole</b> coverage of the L474 inbound guard: the {@code @Tag("slow")}
     * {@code StallRecoveryIntegrationTest.stalledSessionInboundIsRejectedWith408AndClosed} sends no
     * post-stall inbound frame and explicitly does not assert the 408 (D-07 best-effort) — it pins
     * overflow→stall→close only, not any A32 conjunct.
     *
     * <p>Discrimination — the 408-vs-404 payload is what pins the guard. {@code markStalled} clears
     * {@code ATTR_ENTITY_ID}, so with the guard removed an action frame falls through to
     * {@code handleAction}'s null-entity branch and emits {@code E|404|no-active-entity} — NOT 404
     * silence. So the assertion pins the exact byte string {@code E|408|reconnect-required} (a
     * {@code never().queueAction} check would be vacuous: post-{@code markStalled} the null-entity
     * branch returns before {@code queueAction} guard-or-no-guard). {@code clearInvocations} discards
     * {@code markStalled}'s own best-effort OOB 408 so the verified 408 is the inbound guard's.
     *
     * <p>Universality — the clause quantifies over <i>any</i> inbound frame. The guard sits at the top
     * of {@code handleTextMessage}, before {@code PerceptionCodec.decode}, so one frame per distinct
     * path suffices: a decoded action ({@code a|M|1}), a decoded register ({@code r|C}), and
     * undecodable text (the pre-decode path — garbage that would otherwise 400 must 408 instead). A
     * per-frame-kind or post-decode relocation of the guard fails at least one of the three.
     *
     * <p>Distinct session id ("sc408") for the same shared-context reason as the A14 twin. Teardown
     * reaps via the production grace-expiry sweep ({@code ResumeTokenRegistry.onTick} past the grace
     * window) — the single correct reaper: {@code markStalled} left a STALLED token + incremented the
     * stalled-sessions gauge, and only the sweep (not {@code cleanupByEntityId}, whose
     * {@code clearActive} preserves STALLED by contract) removes both, so nothing leaks into the
     * {@code forkEvery=0} JVM.
     */
    @Test
    void stalledSessionInboundRejectedWithReconnectRequired() throws Exception {
        WebSocketSession sc = newAttachedSession("sc408");
        try {
            handler.handleMessage(sc, new TextMessage("r|C"));
            verify(sc, timeout(2000).atLeastOnce()).sendMessage(argThat(
                    msg -> msg instanceof TextMessage tm && tm.getPayload().startsWith("S|")));

            // Transition to STALLED via the public backpressure entry (no overflow needed).
            handler.markStalled(sc, 0L);

            // Isolate the inbound guard: discard the markStalled OOB 408 + close, so the assertions
            // below pin ONLY the stalled-inbound routing. sendOutOfBand is a direct synchronized send
            // (not the async OutboundSender path), so no timeout() poll is needed on the verifies.
            clearInvocations(sc);

            // Frame 1 — a well-formed action on a STALLED session must 408 + close (not fall through
            // to the 404 the null-entity branch would emit with the guard removed).
            String action = PerceptionCodec.encode(new Frame.ActionFrame('M', Optional.of("1")));
            handler.handleMessage(sc, new TextMessage(action));
            verify(sc).sendMessage(argThat(
                    msg -> msg instanceof TextMessage tm
                            && tm.getPayload().equals("E|408|reconnect-required")));
            verify(sc).close(CloseStatus.SERVICE_RESTARTED);

            // Frame 2 — a register frame (different decoded kind) must 408 too.
            clearInvocations(sc);
            handler.handleMessage(sc, new TextMessage("r|C"));
            verify(sc).sendMessage(argThat(
                    msg -> msg instanceof TextMessage tm
                            && tm.getPayload().equals("E|408|reconnect-required")));

            // Frame 3 — undecodable text. This is the distinct path: the guard sits BEFORE
            // PerceptionCodec.decode, so garbage that would otherwise 400 (malformed) must 408
            // instead — pinning "the guard precedes decode", which is what makes a single frame
            // per decoded kind sufficient for the "any inbound frame" clause.
            clearInvocations(sc);
            handler.handleMessage(sc, new TextMessage("!!!not-a-frame!!!"));
            verify(sc).sendMessage(argThat(
                    msg -> msg instanceof TextMessage tm
                            && tm.getPayload().equals("E|408|reconnect-required")));
        } finally {
            // Reap via the production path: the grace-expiry sweep removes the STALLED token,
            // decrements the stalled-sessions gauge, AND invokes cleanupByEntityId as its callback
            // (entity/grid/slot reap). markStalled set expiresAtTick = 0 + graceWindowTicks; the sweep
            // boundary is inclusive (expiresAtTick <= currentTick), so sweeping exactly at
            // graceWindowTicks reaps. Derive it from the config accessor rather than hardcoding the
            // default, so the reaper still fires if the grace window is retuned. A direct
            // cleanupByEntityId would leak the STALLED entry + gauge (clearActive preserves STALLED).
            long graceWindow = admissionConfig.backpressure().graceWindowTicks();
            resumeTokenRegistry.onTick(new TickEvent(graceWindow));
            outboundSender.detachSession("sc408");
        }
    }

    @Test
    void respawnCapEnforced() throws Exception {
        // Sequence: 1 registration + 5 respawns (cap = MAX_RESPAWNS_PER_SESSION),
        // then a 6th attempt that must produce E|429.
        handler.handleMessage(session, new TextMessage("r|C"));
        for (int i = 0; i < 5; i++) {
            handler.markDead(session);
            handler.handleMessage(session, new TextMessage("r|C"));
        }
        // At this point respawnCount = 5 (max). Next attempt must be rejected.
        handler.markDead(session);
        handler.handleMessage(session, new TextMessage("r|C"));

        // The handler drives 7 outbound frames (1 registration ack + 5 respawn acks +
        // the final E|429), all sent ASYNCHRONOUSLY through the per-session VT drain loop.
        // A plain timeout(...).atLeastOnce() returns on the FIRST frame to land, so asserting
        // on the captured values right after races the still-queued E|429 (the LAST frame) —
        // intermittently the cap frame hasn't drained yet and the assertion sees only the
        // earlier acks. (Observed ~12% under a 2-core VT-carrier squeeze.) Bumping the timeout
        // would NOT fix it: atLeastOnce still unblocks on the first frame regardless of the
        // value. Wait for the SPECIFIC E|429 send instead — Mockito polls until that exact
        // frame is observed, which both synchronises on and asserts the cap rejection.
        verify(session, timeout(5000).atLeastOnce()).sendMessage(argThat(
                msg -> msg instanceof TextMessage tm && tm.getPayload().startsWith("E|429")));
    }
}
