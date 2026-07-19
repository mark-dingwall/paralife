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
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
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
    void normalSingleCloseReleasesExactlyOne() throws Exception {
        ObserverSessionGate g = gate(true, 1);
        Map<String, Object> attrs = new HashMap<>();
        assertThat(before(g, attrs)).isTrue();
        WebSocketSession s = sessionWith(attrs);

        g.releaseIfHeld(s);

        assertThat(g.availablePermits()).as("positive control: one close → one release").isEqualTo(1);
    }
}
