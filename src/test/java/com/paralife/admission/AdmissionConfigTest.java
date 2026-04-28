package com.paralife.admission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionConfigTest {

    @SpringBootTest(classes = AdmissionConfigTest.TestApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties = {
            "paralife.tick.auto-start=false",
            "paralife.admission.cap=42",
            "paralife.admission.maintenance=true",
            "paralife.admission.tick-overload.high-water-pct=85",
            "paralife.admission.tick-overload.low-water-pct=55",
            "paralife.admission.tick-overload.window-ticks=20",
            "paralife.admission.backpressure.outbound-queue-size=32",
            "paralife.admission.backpressure.grace-window-ticks=5"
    })
    static class BindsAllKeys {
        @Autowired AdmissionConfig cfg;

        @Test
        void allFieldsBound() {
            assertThat(cfg.cap()).isEqualTo(42);
            assertThat(cfg.maintenance()).isTrue();
            assertThat(cfg.tickOverload().highWaterPct()).isEqualTo(85);
            assertThat(cfg.tickOverload().lowWaterPct()).isEqualTo(55);
            assertThat(cfg.tickOverload().windowTicks()).isEqualTo(20);
            assertThat(cfg.backpressure().outboundQueueSize()).isEqualTo(32);
            assertThat(cfg.backpressure().graceWindowTicks()).isEqualTo(5);
        }
    }

    @Test
    void defaultsConstructorReturnsExpectedValues() {
        AdmissionConfig d = AdmissionConfig.defaults();
        assertThat(d.cap()).isEqualTo(256);
        assertThat(d.maintenance()).isFalse();
        assertThat(d.tickOverload().highWaterPct()).isEqualTo(80);
        assertThat(d.tickOverload().lowWaterPct()).isEqualTo(60);
        assertThat(d.tickOverload().windowTicks()).isEqualTo(10);
        assertThat(d.backpressure().outboundQueueSize()).isEqualTo(128);
        assertThat(d.backpressure().graceWindowTicks()).isEqualTo(10);
    }

    @Test
    void rejectsCapZero() {
        assertThatThrownBy(() ->
                new AdmissionConfig(0, false,
                        AdmissionConfig.TickOverloadConfig.defaults(),
                        AdmissionConfig.BackpressureConfig.defaults(),
                        AdmissionConfig.AttributionConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paralife.admission.cap");
    }

    @Test
    void rejectsHighWaterAtOrBelowLowWater() {
        assertThatThrownBy(() ->
                new AdmissionConfig.TickOverloadConfig(60, 60, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be > low-water-pct");
    }

    @Test
    void rejectsZeroOutboundQueueSize() {
        assertThatThrownBy(() ->
                new AdmissionConfig.BackpressureConfig(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outbound-queue-size");
    }

    @Configuration
    @EnableConfigurationProperties(AdmissionConfig.class)
    static class TestApp {}
}
