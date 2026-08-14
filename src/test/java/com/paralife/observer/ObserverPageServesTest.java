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

    /**
     * Static wiring gate — it proves the page references the extracted modules and the timed
     * render path, NOT that any JavaScript executed. A browser smoke remains deferred by
     * explicit project decision (BACKLOG.md).
     */
    @Test
    void pageDelegatesRenderingToTheExtractedModules() {
        String body = rest.getForEntity("/observer.html", String.class).getBody();
        assertThat(body).isNotNull();

        assertThat(body).as("imports are only honoured inside a module script")
                .contains("type=\"module\"");
        assertThat(body).as("imports the render module").contains("./observer-render.js");
        assertThat(body).as("imports the marker module").contains("./observer-markers.js");
        assertThat(body).as("imports the legend module").contains("./observer-legend.js");
        assertThat(body).as("imports the lightning trail module").contains("./observer-lightning.js");
        assertThat(body).as("world frames go through the extracted painter").contains("drawWorld(");
        assertThat(body).as("R11: the render call is timed with a monotonic clock")
                .contains("performance.now()");
        assertThat(body).as("R11: the measured cost reaches the render-stats text")
                .containsPattern("(?s)renderStatsEl\\.textContent[^;]*renderMs");
        assertThat(body).as("the connection status is not overwritten by a repaint")
                .doesNotContainPattern("(?s)statusEl\\.textContent[^;]*renderMs");
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

    @Test
    void legendModuleIsServedAsStaticContent() {
        ResponseEntity<String> resp = rest.getForEntity("/observer-legend.js", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("the page imports this module by URL — a 404 breaks the page silently").isTrue();
        assertThat(resp.getBody()).as("exports the legend rows").contains("LEGEND_ROWS");
    }

    @Test
    void lightningModuleIsServedAsStaticContent() {
        ResponseEntity<String> resp = rest.getForEntity("/observer-lightning.js", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("the page imports this module by URL — a 404 breaks the page silently").isTrue();
        assertThat(resp.getBody()).as("exports the trail factory").contains("createLightningTrail");
    }
}
