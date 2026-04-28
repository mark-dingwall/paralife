package com.paralife.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level tests for Phase 18 D-06: BotClient injects identity headers on
 * every WebSocket upgrade request.
 *
 * <p>Uses a minimal raw-socket stub server (same pattern as
 * {@link BotClientClosesOnMissingServerDeflateTest}) to capture handshake
 * headers before completing the upgrade. This lets us assert header values at
 * the HTTP layer without bringing up the full Spring context.
 *
 * <p><b>Coverage:</b>
 * <ul>
 *   <li>harness identity → both {@code X-Paralife-Source} and
 *       {@code X-Paralife-Harness} set.</li>
 *   <li>operator identity → only {@code X-Paralife-Source}, no harness header.</li>
 *   <li>unknown identity → only {@code X-Paralife-Source}, no harness header.</li>
 *   <li>Legacy 3-arg constructor defaults to {@code BotIdentity.unknown()}.</li>
 *   <li>Reconnect path re-sends headers (Pitfall 1 constructor-driven mitigation;
 *       full same-instance reconnect-loop coverage lives in Plan 06's
 *       {@code com.paralife.admission.AttributionRebindTest}).</li>
 * </ul>
 *
 * <p><b>Note on reconnect test:</b> This test creates a fresh {@code BotClient}
 * instance for each connect-cycle to verify the "headers re-sent on every
 * connect()" invariant at the constructor-driven level. End-to-end same-instance
 * reconnect-loop coverage (BotClient's internal reconnect after E|408 from
 * STALLED-pivot) lives in
 * {@code com.paralife.admission.AttributionRebindTest} (Plan 06).
 */
class BotClientHandshakeHeaderTest {

    private ServerSocket server;
    private int port;
    private volatile boolean stop;

    /**
     * Captured headers from successive connections. Each entry is the header map
     * from one upgrade request, keyed in lowercase.
     */
    private final CopyOnWriteArrayList<Map<String, String>> capturedHeaders =
            new CopyOnWriteArrayList<>();

    /** Latches to synchronise tests waiting for N connections. */
    private CountDownLatch connectionLatch;

    @BeforeEach
    void startStubServer() throws Exception {
        capturedHeaders.clear();
        server = new ServerSocket(0);
        port = server.getLocalPort();
        Thread.ofVirtual().name("stub-accept").start(this::acceptLoop);
    }

    @AfterEach
    void stopServer() throws Exception {
        stop = true;
        if (server != null && !server.isClosed()) {
            server.close();
        }
    }

    // ── harness identity ──────────────────────────────────────────────────────

    @Test
    void harnessIdentity_sendsBothSourceAndHarnessHeaders() throws Exception {
        connectionLatch = new CountDownLatch(1);
        BotIdentity identity = BotIdentity.harness("harness-A");
        BotClientOptions opts = BotClientOptions.defaults("ws://localhost:" + port + "/ws/world",
                'C', new HeuristicBrain(70));
        opts = new BotClientOptions(opts.serverUri(), opts.species(), opts.brain(),
                opts.respawnCooldownMs(), opts.respawnJitterMs(), opts.rng(), identity);
        BotClient bot = new BotClient(opts);

        Thread.ofVirtual().start(() -> {
            try { bot.connect(); } catch (Exception ignored) {}
        });

        assertThat(connectionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        bot.disconnect();

        assertThat(capturedHeaders).hasSize(1);
        Map<String, String> headers = capturedHeaders.get(0);
        assertThat(headers).containsEntry("x-paralife-source", "harness");
        assertThat(headers).containsEntry("x-paralife-harness", "harness-A");
    }

    // ── operator identity ──────────────────────────────────────────────────────

    @Test
    void operatorIdentity_sendsSourceHeaderOnly() throws Exception {
        connectionLatch = new CountDownLatch(1);
        BotIdentity identity = BotIdentity.operator();
        BotClientOptions opts = new BotClientOptions(
                "ws://localhost:" + port + "/ws/world", 'M', new HeuristicBrain(70),
                100L, 50L, new Random(), identity);
        BotClient bot = new BotClient(opts);

        Thread.ofVirtual().start(() -> {
            try { bot.connect(); } catch (Exception ignored) {}
        });

        assertThat(connectionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        bot.disconnect();

        assertThat(capturedHeaders).hasSize(1);
        Map<String, String> headers = capturedHeaders.get(0);
        assertThat(headers).containsEntry("x-paralife-source", "operator");
        assertThat(headers).doesNotContainKey("x-paralife-harness");
    }

    // ── unknown identity ──────────────────────────────────────────────────────

    @Test
    void unknownIdentity_sendsSourceHeaderOnly() throws Exception {
        connectionLatch = new CountDownLatch(1);
        BotClientOptions opts = BotClientOptions.defaults(
                "ws://localhost:" + port + "/ws/world", 'S', new HeuristicBrain(70));
        // defaults() uses BotIdentity.unknown()
        BotClient bot = new BotClient(opts);

        Thread.ofVirtual().start(() -> {
            try { bot.connect(); } catch (Exception ignored) {}
        });

        assertThat(connectionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        bot.disconnect();

        assertThat(capturedHeaders).hasSize(1);
        Map<String, String> headers = capturedHeaders.get(0);
        assertThat(headers).containsEntry("x-paralife-source", "unknown");
        assertThat(headers).doesNotContainKey("x-paralife-harness");
    }

    // ── legacy constructors default to BotIdentity.unknown() ─────────────────

    @Test
    void legacy3ArgCtor_defaultsToUnknown() throws Exception {
        connectionLatch = new CountDownLatch(1);
        BotClient bot = new BotClient(
                "ws://localhost:" + port + "/ws/world", 'C', new HeuristicBrain(70));

        Thread.ofVirtual().start(() -> {
            try { bot.connect(); } catch (Exception ignored) {}
        });

        assertThat(connectionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        bot.disconnect();

        assertThat(capturedHeaders).hasSize(1);
        Map<String, String> headers = capturedHeaders.get(0);
        assertThat(headers).containsEntry("x-paralife-source", "unknown");
        assertThat(headers).doesNotContainKey("x-paralife-harness");
    }

    // ── reconnect re-sends headers (Pitfall 1 — constructor-driven level) ─────

    @Test
    void reconnect_resendsHeadersOnEveryConnect() throws Exception {
        // Tests the constructor-driven invariant: a new BotClient with the same
        // BotClientOptions re-emits identical headers on connect().
        // End-to-end same-instance reconnect (BotClient.reconnect() after E|408)
        // is covered by Plan 06 AttributionRebindTest.
        BotClientOptions opts = new BotClientOptions(
                "ws://localhost:" + port + "/ws/world", 'C', new HeuristicBrain(70),
                100L, 50L, new Random(), BotIdentity.harness("harness-reconnect"));

        // First connect
        connectionLatch = new CountDownLatch(1);
        BotClient bot1 = new BotClient(opts);
        Thread.ofVirtual().start(() -> {
            try { bot1.connect(); } catch (Exception ignored) {}
        });
        assertThat(connectionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        bot1.disconnect();

        // Second connect (fresh instance, same opts)
        connectionLatch = new CountDownLatch(1);
        BotClient bot2 = new BotClient(opts);
        Thread.ofVirtual().start(() -> {
            try { bot2.connect(); } catch (Exception ignored) {}
        });
        assertThat(connectionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        bot2.disconnect();

        // Both connects must have sent identical headers
        assertThat(capturedHeaders).hasSize(2);
        Map<String, String> h1 = capturedHeaders.get(0);
        Map<String, String> h2 = capturedHeaders.get(1);
        assertThat(h1.get("x-paralife-source")).isEqualTo(h2.get("x-paralife-source"));
        assertThat(h1.get("x-paralife-harness")).isEqualTo(h2.get("x-paralife-harness"));
        assertThat(h1).containsEntry("x-paralife-source", "harness");
        assertThat(h1).containsEntry("x-paralife-harness", "harness-reconnect");
    }

    // ── identity() accessor ────────────────────────────────────────────────────

    @Test
    void identityAccessor_returnsConstructedIdentity() {
        BotIdentity identity = BotIdentity.harness("test-harness");
        BotClientOptions opts = BotClientOptions.defaults(
                "ws://localhost:" + port + "/ws/world", 'C', new HeuristicBrain(70));
        opts = new BotClientOptions(opts.serverUri(), opts.species(), opts.brain(),
                opts.respawnCooldownMs(), opts.respawnJitterMs(), opts.rng(), identity);
        BotClient bot = new BotClient(opts);
        assertThat(bot.identity()).isEqualTo(identity);
    }

    // ── stub server ───────────────────────────────────────────────────────────

    private void acceptLoop() {
        try {
            while (!stop) {
                try {
                    Socket s = server.accept();
                    Thread.ofVirtual().start(() -> handleConnection(s));
                } catch (IOException ioe) {
                    if (!stop) throw new RuntimeException(ioe);
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));

            String line;
            String wsKey = null;
            List<String> rawHeaders = new ArrayList<>();

            // Read request line
            String requestLine = in.readLine();
            if (requestLine == null) return;

            // Read headers
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                rawHeaders.add(line);
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    wsKey = line.substring("sec-websocket-key:".length()).trim();
                }
            }

            // Build case-insensitive header map (lowercase keys)
            Map<String, String> headerMap = new java.util.HashMap<>();
            for (String h : rawHeaders) {
                int colon = h.indexOf(':');
                if (colon > 0) {
                    String key = h.substring(0, colon).trim().toLowerCase();
                    String value = h.substring(colon + 1).trim();
                    headerMap.put(key, value);
                }
            }
            capturedHeaders.add(headerMap);

            // Signal test that a connection was captured
            CountDownLatch latch = this.connectionLatch;
            if (latch != null) {
                latch.countDown();
            }

            // Complete handshake with permessage-deflate so BotClient doesn't reject
            if (wsKey != null) {
                String accept = computeAccept(wsKey);
                OutputStream out = socket.getOutputStream();
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n"
                        + "Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover\r\n"
                        + "\r\n";
                out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }

            // Keep socket open briefly so the client can send its register frame
            // before we close (avoid a Jetty WRITE error racing with teardown).
            try { Thread.sleep(500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception ignored) {}
    }

    private static String computeAccept(String key) throws Exception {
        String magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(magic.getBytes(StandardCharsets.ISO_8859_1));
        return Base64.getEncoder().encodeToString(digest);
    }
}
