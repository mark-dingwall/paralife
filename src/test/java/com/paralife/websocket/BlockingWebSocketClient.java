package com.paralife.websocket;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Phase 17 Plan 11 test-only utility — a raw Jetty 12 WebSocket client whose {@code onTextFrame}
 * callback can be held on a latch to drive deterministic outbound-queue overflow scenarios on the
 * server. Replaces the earlier "instrument BotClient with pause hooks" idea per opencode MEDIUM
 * review: integration tests now own a minimal client they can stall at will.
 *
 * <p>The client negotiates {@code permessage-deflate; server_no_context_takeover} on upgrade so
 * the server's {@code DeflateEnforcementFilter} accepts the connection (D-33).
 *
 * <h2>API surface (M5/M6 reuse)</h2>
 * <ul>
 *   <li>{@link #connect(URI, Duration)} — opens the WS and blocks until {@code OnWebSocketOpen} fires.</li>
 *   <li>{@link #send(String)} — pushes one text frame to the server.</li>
 *   <li>{@link #holdReceive(boolean)} — when set true, the next inbound text frame BLOCKS the
 *       Jetty client thread on a fresh {@link CountDownLatch}, emulating a slow consumer.</li>
 *   <li>{@link #releaseReceive()} — releases any in-progress hold so receive resumes.</li>
 *   <li>{@link #received()} — snapshot of all decoded text frames received so far.</li>
 *   <li>{@link #awaitReceiveCount(int, Duration)} — busy-wait helper.</li>
 *   <li>{@link #close()} — clean shutdown (closes session and stops the underlying client).</li>
 * </ul>
 *
 * <p><b>Concurrency model:</b> {@code received} is a synchronized list. {@code holdLatch} is a
 * volatile reference so the Jetty receive thread sees changes from the test thread immediately.
 */
public class BlockingWebSocketClient {

    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch holdLatch;
    private volatile Session session;
    private volatile int closeCode = -1;
    private volatile String closeReason;
    private volatile Throwable lastError;
    private WebSocketClient client;

    public BlockingWebSocketClient() {}

    /**
     * Connect to {@code uri} and block until {@link OnWebSocketOpen} has fired or {@code timeout}
     * elapses.
     *
     * @throws Exception on upgrade failure or timeout
     */
    public void connect(URI uri, Duration timeout) throws Exception {
        client = new WebSocketClient();
        try {
            client.start();
            ClientUpgradeRequest req = new ClientUpgradeRequest();
            // D-33: server enforces permessage-deflate; advertise it on upgrade.
            req.addExtensions("permessage-deflate; server_no_context_takeover");
            Handler handler = new Handler();
            Session opened = client.connect(handler, uri, req)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            // Defensive: ensure OnWebSocketOpen wired the session reference. Jetty 12 invokes
            // @OnWebSocketOpen synchronously before completing the connect future, so this is
            // a belt-and-braces assignment in case future Jetty versions reorder.
            if (this.session == null) {
                this.session = opened;
            }
        } catch (Exception e) {
            try { client.stop(); } catch (Exception ignored) {}
            client = null;
            throw e;
        }
    }

    /** Send a text frame to the server. Non-blocking (Jetty Callback.NOOP). */
    public void send(String text) throws IOException {
        Session s = this.session;
        if (s == null) throw new IOException("not connected");
        s.sendText(text, Callback.NOOP);
    }

    /**
     * When set true, the next {@link OnWebSocketMessage} invocation blocks on a fresh latch
     * until {@link #releaseReceive()} is called. The hold lives across multiple incoming frames:
     * each frame waits on the same latch until released.
     */
    public void holdReceive(boolean hold) {
        this.holdLatch = hold ? new CountDownLatch(1) : null;
    }

    /** Release the in-progress hold so receive resumes. Idempotent. */
    public void releaseReceive() {
        CountDownLatch l = this.holdLatch;
        this.holdLatch = null;
        if (l != null) l.countDown();
    }

    /** Snapshot of all received text frames since connect. */
    public List<String> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    /**
     * Block until {@code received().size() >= count} OR {@code timeout} elapses.
     * Returns true on success, false on timeout.
     */
    public boolean awaitReceiveCount(int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (received().size() >= count) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return received().size() >= count;
    }

    /** Returns true once the server has closed this client's WS (or the client closed itself). */
    public boolean isClosed() {
        return closeCode >= 0;
    }

    /** Returns the close status code reported by Jetty, or -1 if not yet closed. */
    public int getCloseCode() {
        return closeCode;
    }

    /** Returns the close reason string reported by Jetty, or null if not yet closed. */
    public String getCloseReason() {
        return closeReason;
    }

    /** Returns the most recent error reported via {@code @OnWebSocketError}, or null. */
    public Throwable getLastError() {
        return lastError;
    }

    /** Returns the underlying Jetty session id (for cross-checking against server-side registry). */
    public String sessionId() {
        Session s = this.session;
        return s == null ? null : s.toString();
    }

    /** Wait until the WS is closed by the server, or {@code timeout} elapses. */
    public boolean awaitClose(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isClosed()) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return isClosed();
    }

    /** Close the session and stop the underlying client. Idempotent. */
    public void close() {
        // Release any hold first so the receive thread can exit.
        releaseReceive();
        Session s = this.session;
        if (s != null && s.isOpen()) {
            try { s.close(1000, "test done", Callback.NOOP); } catch (RuntimeException ignored) {}
        }
        if (client != null) {
            try { client.stop(); } catch (Exception ignored) {}
            client = null;
        }
    }

    @WebSocket
    public class Handler {
        @OnWebSocketOpen
        public void onOpen(Session s) {
            BlockingWebSocketClient.this.session = s;
        }

        @OnWebSocketMessage
        public void onText(String message) {
            CountDownLatch l = holdLatch;
            if (l != null) {
                try {
                    l.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            received.add(message);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            BlockingWebSocketClient.this.closeCode = statusCode;
            BlockingWebSocketClient.this.closeReason = reason;
        }

        @OnWebSocketError
        public void onError(Throwable error) {
            BlockingWebSocketClient.this.lastError = error;
        }
    }
}
