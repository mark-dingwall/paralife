package com.paralife.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

class JettyRuntimeConfigTest {

    @Test
    void defaultsMatchProjectCurrentDefaults() {
        // Project-current defaults = Jetty 12.0.18 defaults except idleTimeoutMs=60000
        // (Jetty's own default is 30000; the project inherits the pre-existing legacy
        // paralife.websocket.idle-timeout-ms value for back-compat — Pass-2 Concern #16).
        JettyRuntimeConfig c = JettyRuntimeConfig.defaults();
        assertThat(c.inputBufferSize()).isEqualTo(4096);
        assertThat(c.outputBufferSize()).isEqualTo(4096);
        assertThat(c.maxFrameSize()).isEqualTo(65536L);
        assertThat(c.maxBinaryMessageSize()).isEqualTo(65536L);
        assertThat(c.maxTextMessageSize()).isEqualTo(65536L);
        assertThat(c.idleTimeoutMs()).isEqualTo(60000L); // project-current default, not Jetty's 30000
        assertThat(c.autoFragment()).isTrue();
        assertThat(c.maxOutgoingFrames()).isEqualTo(-1); // Jetty default: unlimited (delegate to D-10 queue)
    }

    @Test
    void rejectsInputBufferTooSmall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JettyRuntimeConfig(128, 4096, 65536L, 65536L, 65536L, 60000L, true, -1));
        assertThat(ex.getMessage()).contains("paralife.runtime.jetty.input-buffer-size");
    }

    @Test
    void rejectsOutputBufferTooSmall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JettyRuntimeConfig(4096, 128, 65536L, 65536L, 65536L, 60000L, true, -1));
        assertThat(ex.getMessage()).contains("paralife.runtime.jetty.output-buffer-size");
    }

    @Test
    void rejectsMaxFrameTooSmall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JettyRuntimeConfig(4096, 4096, 512L, 65536L, 65536L, 60000L, true, -1));
        assertThat(ex.getMessage()).contains("paralife.runtime.jetty.max-frame-size");
    }

    @Test
    void rejectsMaxBinaryTooSmall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JettyRuntimeConfig(4096, 4096, 65536L, 512L, 65536L, 60000L, true, -1));
        assertThat(ex.getMessage()).contains("paralife.runtime.jetty.max-binary-message-size");
    }

    @Test
    void rejectsMaxTextTooSmall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 512L, 60000L, true, -1));
        assertThat(ex.getMessage()).contains("paralife.runtime.jetty.max-text-message-size");
    }

    @Test
    void rejectsIdleTimeoutTooSmall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 65536L, 500L, true, -1));
        assertThat(ex.getMessage()).contains("paralife.runtime.jetty.idle-timeout-ms");
    }

    @Test
    void rejectsMaxOutgoingFramesZero() {
        // 0 is neither the -1 unlimited sentinel nor a positive cap >= 1.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 65536L, 60000L, true, 0));
        assertThat(ex.getMessage()).contains("paralife.runtime.jetty.max-outgoing-frames");
    }

    @Test
    void acceptsMaxOutgoingFramesUnlimitedAndPositive() {
        // -1 (unlimited) and a positive cap both pass the carve-out.
        assertThat(new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 65536L, 60000L, true, -1).maxOutgoingFrames())
                .isEqualTo(-1);
        assertThat(new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 65536L, 60000L, true, 8).maxOutgoingFrames())
                .isEqualTo(8);
    }

    @Configuration
    @EnableConfigurationProperties(JettyRuntimeConfig.class)
    static class TestApp {
    }

    @SpringBootTest(classes = JettyRuntimeConfigTest.TestApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties = {
            "paralife.runtime.jetty.input-buffer-size=8192",
            "paralife.runtime.jetty.idle-timeout-ms=30000"
    })
    static class BindingRoundTripTest {
        @Autowired
        JettyRuntimeConfig cfg;

        @Test
        void overridesAndDefaultsCoexist() {
            assertThat(cfg.inputBufferSize()).isEqualTo(8192);
            assertThat(cfg.idleTimeoutMs()).isEqualTo(30000L);
            assertThat(cfg.outputBufferSize()).isEqualTo(4096); // default preserved
            assertThat(cfg.maxFrameSize()).isEqualTo(65536L); // default preserved
            assertThat(cfg.maxBinaryMessageSize()).isEqualTo(65536L); // default preserved
            assertThat(cfg.maxTextMessageSize()).isEqualTo(65536L); // default preserved
            assertThat(cfg.autoFragment()).isTrue();
            assertThat(cfg.maxOutgoingFrames()).isEqualTo(-1); // default preserved
        }
    }
}
