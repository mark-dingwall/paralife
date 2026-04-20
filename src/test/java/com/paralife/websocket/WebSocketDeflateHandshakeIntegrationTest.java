package com.paralife.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-31 / D-32: the server advertises {@code permessage-deflate; server_no_context_takeover}
 * on the upgrade response. Uses a raw socket to craft the HTTP/1.1 upgrade request because
 * {@link java.net.http.HttpClient} refuses to set the restricted {@code Connection} and
 * {@code Upgrade} headers, and Spring's WebSocket client APIs hide the handshake response
 * headers before any frames flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WebSocketDeflateHandshakeIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void serverNegotiatesPermessageDeflateWithNoContextTakeover() throws Exception {
        UpgradeResult result = sendRawUpgrade(port,
                "GET /ws/world HTTP/1.1\r\n"
                        + "Host: localhost:" + port + "\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover\r\n"
                        + "\r\n");

        assertEquals(101, result.statusCode,
                "Server should complete upgrade with status 101, got "
                        + result.statusCode + " (" + result.statusLine + ")");
        String ext = result.firstHeader("sec-websocket-extensions");
        assertNotNull(ext, "No Sec-WebSocket-Extensions response header on 101 upgrade");
        assertTrue(ext.contains("permessage-deflate"),
                "Response extensions missing permessage-deflate: " + ext);
        assertTrue(ext.contains("server_no_context_takeover"),
                "Response extensions missing server_no_context_takeover: " + ext);
    }

    static UpgradeResult sendRawUpgrade(int port, String rawRequest) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = in.readLine();
            if (statusLine == null) {
                throw new AssertionError("No response from server");
            }
            int statusCode = parseStatusCode(statusLine);

            List<String[]> headers = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                headers.add(new String[]{name, value});
            }
            return new UpgradeResult(statusLine, statusCode, headers);
        }
    }

    private static int parseStatusCode(String statusLine) {
        // HTTP/1.1 101 Switching Protocols
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new AssertionError("Malformed status line: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new AssertionError("Malformed status code in: " + statusLine);
        }
    }

    static final class UpgradeResult {
        final String statusLine;
        final int statusCode;
        final List<String[]> headers;

        UpgradeResult(String statusLine, int statusCode, List<String[]> headers) {
            this.statusLine = statusLine;
            this.statusCode = statusCode;
            this.headers = headers;
        }

        String firstHeader(String lowercaseName) {
            for (String[] h : headers) {
                if (h[0].equals(lowercaseName)) {
                    return h[1];
                }
            }
            return null;
        }
    }
}
