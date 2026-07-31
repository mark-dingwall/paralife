package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * The observer page ships as static content in the app (no build pipeline). This is
 * a serves-check only; render fidelity is judged by eye per the spec. NOTE a serves
 * check alone would pass with broken JS — the end-to-end handshake + parse is covered
 * by ObserverEndpointIntegrationTest (real frames) — so this asserts the canvas +
 * WS-client scaffolding is present, not just HTTP 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObserverPageServesTest {

    @Autowired TestRestTemplate rest;

    @Test
    void observerHtmlIsServedWithCanvasAndWsClient() {
        ResponseEntity<String> resp = rest.getForEntity("/observer.html", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).as("has a grid canvas").contains("<canvas");
        assertThat(body).as("connects to the observer endpoint").contains("/ws/observer");
        assertThat(body).as("handles both frame types").contains("bootstrap").contains("world");
    }

    @Test
    void markerModuleIsServedAsStaticContent() {
        ResponseEntity<String> resp = rest.getForEntity("/observer-markers.js", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("the page imports this module by URL — a 404 breaks the page silently").isTrue();
        assertThat(resp.getBody()).as("exports the marker geometry entry point").contains("markerOps");
    }

    @Test
    void renderModuleIsServedAsStaticContent() {
        ResponseEntity<String> resp = rest.getForEntity("/observer-render.js", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).as("exports the world painter").contains("drawWorld");
    }
}
