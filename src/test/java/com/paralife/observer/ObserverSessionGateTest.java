package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class ObserverSessionGateTest {

    private static ObserverSessionGate gate(boolean enabled, int max) {
        return new ObserverSessionGate(new ObserverConfig(enabled, max));
    }

    private static boolean before(ObserverSessionGate g, Map<String, Object> attrs) throws Exception {
        return g.beforeHandshake(mock(ServerHttpRequest.class), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attrs);
    }

    private static WebSocketSession sessionWith(Map<String, Object> handshakeAttrs) {
        WebSocketSession s = mock(WebSocketSession.class);
        // handshake attrs become session attributes
        when(s.getAttributes()).thenReturn(new ConcurrentHashMap<>(handshakeAttrs));
        return s;
    }

    @Test
    void disabledRefusesEveryHandshake() throws Exception {
        ObserverSessionGate g = gate(false, 4);
        assertThat(before(g, new HashMap<>())).as("disabled → refuse").isFalse();
        assertThat(g.availablePermits()).as("no permit consumed when disabled").isEqualTo(4);
    }

    @Test
    void capEnforcedAtEstablishSequentially() throws Exception {
        ObserverSessionGate g = gate(true, 2);
        assertThat(g.acquireForSession(sessionWith(new HashMap<>()))).isTrue();
        assertThat(g.acquireForSession(sessionWith(new HashMap<>()))).isTrue();
        assertThat(g.acquireForSession(sessionWith(new HashMap<>())))
                .as("third establish refused at cap 2").isFalse();
        assertThat(g.availablePermits()).isZero();
        // Once full, beforeHandshake fast-rejects to spare the doomed upgrade round-trip.
        assertThat(before(g, new HashMap<>())).as("handshake fast-rejected when full").isFalse();
    }

    @Test
    void capNeverExceededUnderConcurrentEstablishStampede() throws Exception {
        // The Semaphore's whole reason is that `size() < max; add()` is a check-then-act race.
        // Acquisition is now at establish, so fire far more concurrent establishes than the cap
        // behind a barrier and assert EXACTLY maxSessions win. (RED-test by swapping the Semaphore
        // for a plain `if (count < max) count++` — this stampede then admits > maxSessions.)
        int max = 4, callers = 16;
        ObserverSessionGate g = gate(true, max);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        AtomicInteger successes = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(callers);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(); // release all callers at once
                    if (g.acquireForSession(sessionWith(new HashMap<>()))) successes.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(successes.get()).as("exactly maxSessions win the establish stampede").isEqualTo(max);
        assertThat(g.availablePermits()).as("permits exhausted, never negative").isZero();
    }

    @Test
    void releaseIsExactlyOnceAcrossErrorThenClose() {
        ObserverSessionGate g = gate(true, 2);
        WebSocketSession s = sessionWith(new HashMap<>());
        assertThat(g.acquireForSession(s)).isTrue();
        assertThat(g.availablePermits()).isEqualTo(1);

        g.releaseIfHeld(s); // handleTransportError path
        g.releaseIfHeld(s); // afterConnectionClosed path (duplicate)

        assertThat(g.availablePermits())
                .as("both close/error paths fire but the permit releases exactly once")
                .isEqualTo(2);
    }

    @Test
    void upgradeCommittedButSessionNeverOpensLeaksNoPermit() throws Exception {
        // O9 leak (review finding), now closed: the permit is NOT taken at the handshake, so an
        // upgrade that commits 101 and then dies before the WS session opens — firing no session
        // lifecycle callback (onClose only follows onOpen) — strands nothing. Acquisition happens
        // only at afterConnectionEstablished, whose afterConnectionClosed is guaranteed to free it.
        ObserverSessionGate g = gate(true, 1);
        assertThat(before(g, new HashMap<>())).as("handshake admitted, no permit taken yet").isTrue();

        // 101 committed, then the socket dies before onOpen — no session, no callback.
        MockHttpServletResponse upgraded = new MockHttpServletResponse();
        upgraded.setStatus(HttpStatus.SWITCHING_PROTOCOLS.value());
        g.afterHandshake(mock(ServerHttpRequest.class), new ServletServerHttpResponse(upgraded),
                mock(WebSocketHandler.class), null);

        assertThat(g.availablePermits())
                .as("a committed-but-never-opened upgrade strands no permit").isEqualTo(1);
        // Positive control: the permit is still there for a session that actually opens.
        assertThat(g.acquireForSession(sessionWith(new HashMap<>())))
                .as("acquisition happens at establish, not handshake").isTrue();
        assertThat(g.availablePermits()).isZero();
    }

    @Test
    void normalSingleCloseReleasesExactlyOne() {
        ObserverSessionGate g = gate(true, 1);
        WebSocketSession s = sessionWith(new HashMap<>());
        assertThat(g.acquireForSession(s)).isTrue();

        g.releaseIfHeld(s);

        assertThat(g.availablePermits()).as("positive control: one close → one release").isEqualTo(1);
    }
}
