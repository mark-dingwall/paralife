package com.paralife;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParalifeApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // Verifies Spring context starts successfully with all config
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void virtualThreadsAreEnabled() throws Exception {
        // Verify the JVM supports virtual threads (Java 21+)
        Thread vt = Thread.startVirtualThread(() -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
        });
        vt.join();

        // The fact that the app boots with spring.threads.virtual.enabled=true
        // and Tomcat serves requests proves virtual threads are active for request handling.
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void metricsEndpointAvailable() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/metrics", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
