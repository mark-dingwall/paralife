package com.paralife.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-33 fail-fast enforcement: the server MUST refuse the upgrade when a client
 * omits the {@code permessage-deflate} extension. Mitigation for threat T-15-02
 * (extension downgrade / silent fallback to uncompressed traffic).
 *
 * <p>Uses a raw socket (see {@link WebSocketDeflateHandshakeIntegrationTest#sendRawUpgrade})
 * because {@link java.net.http.HttpClient} forbids setting {@code Connection} / {@code Upgrade}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class ServerRefusesUpgradeWithoutDeflateTest {

    @LocalServerPort
    private int port;

    @Test
    void serverRejectsUpgradeLackingPermessageDeflate() throws Exception {
        // Upgrade request WITHOUT Sec-WebSocket-Extensions header.
        WebSocketDeflateHandshakeIntegrationTest.UpgradeResult result =
                WebSocketDeflateHandshakeIntegrationTest.sendRawUpgrade(port,
                        "GET /ws/world HTTP/1.1\r\n"
                                + "Host: localhost:" + port + "\r\n"
                                + "Connection: Upgrade\r\n"
                                + "Upgrade: websocket\r\n"
                                + "Sec-WebSocket-Version: 13\r\n"
                                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                                + "\r\n");

        assertNotEquals(101, result.statusCode,
                "Server must NOT complete upgrade without permessage-deflate; got "
                        + result.statusCode + " (" + result.statusLine + ")");
        assertTrue(result.statusCode >= 400 && result.statusCode < 500,
                "Server should respond 4xx on refusal; got " + result.statusCode
                        + " (" + result.statusLine + ")");
    }
}
