package com.paralife.admission;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 hardening (F10) — concurrent admission cap-overshoot regression.
 *
 * <p>Spins up 20 bot connections in parallel against {@code paralife.admission.cap=5}.
 * Without the atomic-reservation fix (A4) the check-then-act guard could let &gt;5
 * sessions through. This test enforces the post-fix invariants:
 * <ul>
 *   <li>{@code worldGrid.livingEntityCount() <= 5} after all decisions complete.</li>
 *   <li>At least 15 sessions received {@code E|429|world-full}.</li>
 *   <li>{@code paralife.admission.rejected{reason=world-full}} counter &gt;= 15.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=200",
        "paralife.tick.auto-start=true",
        "paralife.world.width=64",
        "paralife.world.height=64",
        "paralife.admission.cap=5"
})
@Tag("slow")
class ConcurrentAdmissionTest {

    private static final int BOT_COUNT = 20;
    private static final int CAP = 5;

    @LocalServerPort int port;
    @Autowired WorldGrid worldGrid;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void concurrentRegistrationDoesNotOvershootCap() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(BOT_COUNT);
        CountDownLatch decided = new CountDownLatch(BOT_COUNT);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Bot> bots = new CopyOnWriteArrayList<>();

        for (int i = 0; i < BOT_COUNT; i++) {
            final int idx = i;
            Thread.startVirtualThread(() -> {
                Bot bot = new Bot("bot-" + idx, port, decided, admitted, rejected);
                bots.add(bot);
                try {
                    bot.client = new WebSocketClient();
                    bot.client.start();
                    ClientUpgradeRequest req = new ClientUpgradeRequest();
                    req.addExtensions("permessage-deflate; server_no_context_takeover");
                    bot.session = bot.client.connect(new Endpoint(bot),
                                    URI.create("ws://localhost:" + port + "/ws/world"), req)
                            .get(10, TimeUnit.SECONDS);
                    barrier.await(10, TimeUnit.SECONDS);
                    bot.session.sendText(PerceptionCodec.encode(new Frame.RegisterFrame('C')),
                            Callback.NOOP);
                } catch (Exception e) {
                    decided.countDown();
                }
            });
        }

        assertThat(decided.await(30, TimeUnit.SECONDS))
                .as("All %d bots reach a decision within 30s", BOT_COUNT)
                .isTrue();

        try {
            // Verify grid never overshot cap.
            assertThat(worldGrid.livingEntityCount())
                    .as("Living entity count must not exceed cap=%d", CAP)
                    .isLessThanOrEqualTo(CAP);

            int rejectedCount = rejected.get();
            assertThat(rejectedCount)
                    .as("At least %d rejections expected (cap=%d, %d bots)",
                            BOT_COUNT - CAP, CAP, BOT_COUNT)
                    .isGreaterThanOrEqualTo(BOT_COUNT - CAP);

            double counter = meterRegistry.counter(AdmissionMetrics.M_REJECTED,
                    "reason", RejectionToken.WORLD_FULL).count();
            assertThat(counter)
                    .as("rejected{reason=world-full} counter at least %d", BOT_COUNT - CAP)
                    .isGreaterThanOrEqualTo(BOT_COUNT - CAP);
        } finally {
            for (Bot b : new ArrayList<>(bots)) b.close();
        }
    }

    static final class Bot {
        final String id;
        final int port;
        final CountDownLatch decided;
        final AtomicInteger admitted;
        final AtomicInteger rejected;
        volatile boolean settled;
        WebSocketClient client;
        Session session;

        Bot(String id, int port, CountDownLatch decided,
            AtomicInteger admitted, AtomicInteger rejected) {
            this.id = id;
            this.port = port;
            this.decided = decided;
            this.admitted = admitted;
            this.rejected = rejected;
        }

        synchronized void settle(boolean ok) {
            if (settled) return;
            settled = true;
            (ok ? admitted : rejected).incrementAndGet();
            decided.countDown();
        }

        void close() {
            try { if (session != null && session.isOpen()) session.close(1000, "done", Callback.NOOP); } catch (Exception ignored) {}
            try { if (client != null) client.stop(); } catch (Exception ignored) {}
        }
    }

    @WebSocket
    public static class Endpoint {
        private final Bot bot;
        public Endpoint(Bot bot) { this.bot = bot; }

        @OnWebSocketOpen public void onOpen(Session s) { /* captured by caller */ }

        @OnWebSocketMessage
        public void onMessage(String payload) {
            try {
                Frame f = PerceptionCodec.decode(payload);
                if (f instanceof Frame.SyncFrame) {
                    bot.settle(true);
                } else if (f instanceof Frame.ErrorFrame ef && ef.code() == 429) {
                    bot.settle(false);
                }
            } catch (Exception ignored) { /* best-effort */ }
        }
    }
}
