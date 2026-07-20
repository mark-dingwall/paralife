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
    void capEnforcedSequentially() throws Exception {
        ObserverSessionGate g = gate(true, 2);
        assertThat(before(g, new HashMap<>())).isTrue();
        assertThat(before(g, new HashMap<>())).isTrue();
        assertThat(before(g, new HashMap<>())).as("third refused at cap 2").isFalse();
        assertThat(g.availablePermits()).isZero();
    }

    @Test
    void capNeverExceededUnderConcurrentHandshakeStampede() throws Exception {
        // The design's whole reason for a Semaphore is that `size() < max; add()` is a
        // check-then-act race. Fire far more callers than the cap simultaneously behind a
        // barrier and assert EXACTLY maxSessions win. (RED-test by swapping the Semaphore for a
        // plain `if (count < max) count++` — this stampede then admits > maxSessions.)
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
                    if (before(g, new HashMap<>())) successes.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(successes.get()).as("exactly maxSessions win the stampede").isEqualTo(max);
        assertThat(g.availablePermits()).as("permits exhausted, never negative").isZero();
    }

    @Test
    void releaseIsExactlyOnceAcrossErrorThenClose() throws Exception {
        ObserverSessionGate g = gate(true, 2);
        Map<String, Object> attrs = new HashMap<>();
        assertThat(before(g, attrs)).isTrue();
        assertThat(g.availablePermits()).isEqualTo(1);

        WebSocketSession s = sessionWith(attrs); // carries ATTR_PERMIT marker
        g.releaseIfHeld(s); // handleTransportError path
        g.releaseIfHeld(s); // afterConnectionClosed path (duplicate)

        assertThat(g.availablePermits())
                .as("both close/error paths fire but the permit releases exactly once")
                .isEqualTo(2);
        assertThat(g.availablePermits())
                .as("cap never inflated above maxSessions").isLessThanOrEqualTo(2);
    }

    @Test
    void rejectedHandshakeWithoutExceptionReleasesPermit() throws Exception {
        // beforeHandshake acquires the permit BEFORE the upgrade is validated. Spring rejects a
        // malformed upgrade (bad headers / missing key / unsupported version) by returning false
        // from doHandshake WITHOUT throwing, so afterHandshake sees exception==null and a non-101
        // status, and NO session is established (releaseIfHeld never runs). The permit must be
        // returned here or maxSessions bad requests wedge the cap at 503 permanently.
        ObserverSessionGate g = gate(true, 1);
        assertThat(before(g, new HashMap<>())).isTrue();
        assertThat(g.availablePermits()).as("permit acquired at handshake").isZero();

        // Spring passes a ServletServerHttpResponse; a rejected upgrade carries a 4xx status.
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        rejected.setStatus(HttpStatus.BAD_REQUEST.value());
        g.afterHandshake(mock(ServerHttpRequest.class), new ServletServerHttpResponse(rejected),
                mock(WebSocketHandler.class), null);

        assertThat(g.availablePermits())
                .as("a non-switching handshake (no session) releases its permit").isEqualTo(1);
    }

    @Test
    void successfulUpgradeDefersReleaseToTheSession() throws Exception {
        // positive control: a real upgrade returns 101 SWITCHING_PROTOCOLS and establishes a
        // session that owns the permit (freed by releaseIfHeld on close). afterHandshake must NOT
        // release here too — that would double-release and inflate the cap above maxSessions.
        ObserverSessionGate g = gate(true, 1);
        Map<String, Object> attrs = new HashMap<>();
        assertThat(before(g, attrs)).isTrue();

        MockHttpServletResponse upgraded = new MockHttpServletResponse();
        upgraded.setStatus(HttpStatus.SWITCHING_PROTOCOLS.value()); // 101 → real upgrade
        g.afterHandshake(mock(ServerHttpRequest.class), new ServletServerHttpResponse(upgraded),
                mock(WebSocketHandler.class), null);

        assertThat(g.availablePermits()).as("established session still holds its permit").isZero();
        g.releaseIfHeld(sessionWith(attrs)); // the session's own close releases exactly once
        assertThat(g.availablePermits()).as("no double-release on the success path").isEqualTo(1);
    }

    @Test
    void normalSingleCloseReleasesExactlyOne() throws Exception {
        ObserverSessionGate g = gate(true, 1);
        Map<String, Object> attrs = new HashMap<>();
        assertThat(before(g, attrs)).isTrue();
        WebSocketSession s = sessionWith(attrs);

        g.releaseIfHeld(s);

        assertThat(g.availablePermits()).as("positive control: one close → one release").isEqualTo(1);
    }
}
