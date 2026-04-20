package com.paralife.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural half of the single-wiring-path invariant (Consensus #3).
 *
 * <p>Drives a real WebSocket upgrade with {@code permessage-deflate;
 * server_no_context_takeover}, sends a malformed text frame through the upgraded
 * connection, and asserts that
 * {@link WorldWebSocketHandler#handleTextMessage(org.springframework.web.socket.WebSocketSession,
 * org.springframework.web.socket.TextMessage)} runs — a malformed frame makes the
 * handler reply with an {@code Error} message over the socket.
 *
 * <p>The {@link WebSocketRouteAssertion} bean has already validated the
 * "exactly one handler path" invariant by the time Spring completes startup;
 * autowiring it here confirms the bean is present (non-null). Together with
 * the live probe, both the structural and behavioural halves of the invariant
 * are asserted.
 *
 * <p>A raw socket drives the handshake + frame exchange because JSR-356's
 * {@link org.springframework.web.socket.client.standard.StandardWebSocketClient}
 * does not expose {@code Sec-WebSocket-Extensions} in its request headers in a
 * way that reaches Jetty's negotiator — the test would be rejected by the
 * deflate filter. The raw path gives us full control over the upgrade request.
 * Deflate is not applied to the outbound frame (we send an uncompressed text
 * frame with RSV1 bit clear); Jetty accepts a non-compressed frame on a
 * compressed session per RFC 7692.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WebSocketRouteAssertionTest {

    @LocalServerPort int port;
    @Autowired WebSocketRouteAssertion assertion;
    @Autowired WorldWebSocketHandler handler;

    @Test
    void exactlyOnePathReachesHandleTextMessage() throws Exception {
        // If the assertion bean threw during ApplicationReadyEvent, Spring would
        // have aborted startup and @Autowired would fail. A non-null bean here
        // means the structural invariant passed (exactly 1 registration).
        assertNotNull(assertion, "WebSocketRouteAssertion bean should exist");
        assertNotNull(handler, "WorldWebSocketHandler bean should exist");

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 1. Upgrade.
            String handshake = "GET /ws/world HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover\r\n"
                    + "\r\n";
            out.write(handshake.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            // Read response headers line by line until blank line.
            String statusLine = readLine(in);
            assertTrue(statusLine != null && statusLine.contains("101"),
                    "Expected 101 Switching Protocols, got: " + statusLine);
            while (true) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) {
                    break;
                }
            }

            // 2. Send a deliberately malformed text frame. The post-plan-15-06
            // handler's PerceptionCodec.decode() rejects it and replies with
            // E|400|Malformed frame (see WorldWebSocketHandler line ~109). The
            // plan-15-06 rewrite also dropped the post-connect Welcome frame
            // (see afterConnectionEstablished), so this test no longer drains
            // an initial Welcome — the first server→client frame is the error
            // reply to our probe.
            writeMaskedTextFrame(out, "GARBAGE");
            out.flush();

            // 3. Read the reply. Must contain the Error reply produced from
            // inside handleTextMessage — proof the frame reached it.
            WsFrame reply = readFrame(in);
            assertNotNull(reply, "No reply frame from server within 5s — "
                    + "text frame did not reach WorldWebSocketHandler.handleTextMessage");
            String payload = decodePayload(reply);
            assertTrue(payload.startsWith("E|400"),
                    "Expected E|400|... error reply, got: " + payload);
        }
    }

    // --- minimal WS frame IO (RFC 6455) ---

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (prev == '\r' && c == '\n') {
                return sb.substring(0, sb.length() - 1);
            }
            sb.append((char) c);
            prev = c;
        }
    }

    private static WsFrame readFrame(InputStream in) throws Exception {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 < 0 || b2 < 0) {
            return null;
        }
        boolean compressed = (b1 & 0x40) != 0; // RSV1
        int opcode = b1 & 0x0F;
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        if (len == 126) {
            len = ((long)(in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | (in.read() & 0xFF);
            }
        }
        byte[] maskKey = new byte[0];
        if (masked) {
            maskKey = in.readNBytes(4);
        }
        byte[] payload = in.readNBytes((int) len);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }
        return new WsFrame(opcode, compressed, payload);
    }

    private static String decodePayload(WsFrame frame) throws Exception {
        if (!frame.compressed) {
            return new String(frame.payload, StandardCharsets.UTF_8);
        }
        // Deflate payload: append 00 00 FF FF tail per RFC 7692 and inflate raw.
        byte[] deflated = new byte[frame.payload.length + 4];
        System.arraycopy(frame.payload, 0, deflated, 0, frame.payload.length);
        deflated[frame.payload.length] = 0x00;
        deflated[frame.payload.length + 1] = 0x00;
        deflated[frame.payload.length + 2] = (byte) 0xFF;
        deflated[frame.payload.length + 3] = (byte) 0xFF;
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        inflater.setInput(deflated);
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while (!inflater.finished() && !inflater.needsInput()) {
            int n = inflater.inflate(buf);
            if (n == 0) break;
            bos.write(buf, 0, n);
        }
        inflater.end();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeMaskedTextFrame(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(14 + payload.length);
        // FIN=1, RSV1=0 (uncompressed), opcode=1 (text)
        buf.put((byte) 0x81);
        // Client frames MUST be masked (RFC 6455 §5.1).
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        int len = payload.length;
        if (len < 126) {
            buf.put((byte) (0x80 | len));
        } else if (len < 65536) {
            buf.put((byte) (0x80 | 126));
            buf.put((byte) ((len >> 8) & 0xFF));
            buf.put((byte) (len & 0xFF));
        } else {
            buf.put((byte) (0x80 | 127));
            for (int i = 7; i >= 0; i--) {
                buf.put((byte) ((len >> (i * 8)) & 0xFF));
            }
        }
        buf.put(mask);
        for (int i = 0; i < payload.length; i++) {
            buf.put((byte) (payload[i] ^ mask[i % 4]));
        }
        buf.flip();
        byte[] frame = new byte[buf.remaining()];
        buf.get(frame);
        out.write(frame);
    }

    private static final class WsFrame {
        final int opcode;
        final boolean compressed;
        final byte[] payload;

        WsFrame(int opcode, boolean compressed, byte[] payload) {
            this.opcode = opcode;
            this.compressed = compressed;
            this.payload = payload;
        }
    }
}
