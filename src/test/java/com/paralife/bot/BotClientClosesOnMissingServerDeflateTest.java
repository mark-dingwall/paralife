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
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * D-33 client-side enforcement (threat T-15-02). When a server completes the
 * WebSocket upgrade with status 101 but does NOT echo a {@code permessage-deflate}
 * extension, the {@link BotClient} must close the session and surface
 * {@link IllegalStateException} to the caller of {@code connect()}.
 *
 * <p>We stand up a minimal raw-socket server that:
 * <ol>
 *   <li>Completes the WebSocket handshake per RFC 6455 §4.2 (101 Switching
 *       Protocols + correctly-computed {@code Sec-WebSocket-Accept}).</li>
 *   <li>Deliberately OMITS {@code Sec-WebSocket-Extensions} from the response,
 *       simulating an out-of-spec server that accepts the request's deflate
 *       offer but forgets to echo the extension.</li>
 * </ol>
 * The client should then fail the D-33 gate, close the connection, and throw.
 */
class BotClientClosesOnMissingServerDeflateTest {

    private ServerSocket server;
    private int port;
    private Thread acceptThread;
    private final CountDownLatch handshakeDone = new CountDownLatch(1);
    private volatile boolean stop;

    @BeforeEach
    void startStubServer() throws Exception {
        server = new ServerSocket(0);
        port = server.getLocalPort();
        acceptThread = Thread.ofVirtual().name("stub-ws-accept").start(() -> {
            try {
                while (!stop) {
                    try {
                        Socket s = server.accept();
                        Thread.ofVirtual().start(() -> handleHandshake(s));
                    } catch (IOException ioe) {
                        if (!stop) throw new RuntimeException(ioe);
                    }
                }
            } catch (Exception e) {
                // swallowed — test teardown closes the socket
            }
        });
    }

    @AfterEach
    void stopServer() throws Exception {
        stop = true;
        if (server != null && !server.isClosed()) server.close();
        if (acceptThread != null) acceptThread.join(1000);
    }

    @Test
    void clientClosesWhenServerOmitsPermessageDeflate() throws Exception {
        BotClient bot = new BotClient("ws://localhost:" + port + "/ws/world",
                'C', new HeuristicBrain(70), 1000L, 500L);

        // connect() must either throw IllegalStateException (post-gate close)
        // OR complete but leave session closed. Either way isConnected() must
        // be false within a short timeout.
        try {
            assertThrows(Exception.class, bot::connect,
                    "connect() must surface the D-33 gate failure");
        } finally {
            // Give the close handshake a moment to propagate.
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            assertFalse(bot.isConnected(),
                    "Bot must not be considered connected after D-33 gate close");
            bot.disconnect();
        }
    }

    /**
     * Minimal WebSocket handshake handler per RFC 6455 §4.2.1 — completes the
     * upgrade with a correct {@code Sec-WebSocket-Accept} but deliberately
     * omits {@code Sec-WebSocket-Extensions}.
     */
    private void handleHandshake(Socket socket) {
        try (socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String line;
            String wsKey = null;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    wsKey = line.substring("sec-websocket-key:".length()).trim();
                }
            }
            if (wsKey == null) {
                handshakeDone.countDown();
                return;
            }
            String accept = computeAccept(wsKey);
            OutputStream out = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    // Intentionally no Sec-WebSocket-Extensions.
                    + "\r\n";
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            handshakeDone.countDown();
            // Keep socket open briefly so the client's close frame has a
            // chance to arrive before we tear down.
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception ignored) {
            // socket teardown race — fine in a stub server
        }
    }

    private static String computeAccept(String key) throws Exception {
        String magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(magic.getBytes(StandardCharsets.ISO_8859_1));
        return Base64.getEncoder().encodeToString(digest);
    }
}
