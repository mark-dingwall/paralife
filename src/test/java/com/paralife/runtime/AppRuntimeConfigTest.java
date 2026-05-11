package com.paralife.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.paralife.admission.AdmissionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

class AppRuntimeConfigTest {

    @Test
    void defaultsAreNested() {
        AppRuntimeConfig c = AppRuntimeConfig.defaults();
        assertThat(c.outbound().queueWatermarkPct()).isEqualTo(80);
        assertThat(c.outbound().frameSizeBudgetBytes()).isEqualTo(1024);
        assertThat(c.encode().parallelEncodeThreshold()).isEqualTo(-1);
        assertThat(c.encode().encodeBatchHint()).isEqualTo(8);
    }

    @Test
    void rejectsWatermarkOutOfRange() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AppRuntimeConfig.OutboundConfig(0, 1024));
        assertThat(ex.getMessage()).contains("paralife.runtime.app.outbound.queue-watermark-pct");

        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> new AppRuntimeConfig.OutboundConfig(101, 1024));
        assertThat(ex2.getMessage()).contains("paralife.runtime.app.outbound.queue-watermark-pct");
    }

    @Test
    void rejectsFrameBudgetTooSmall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AppRuntimeConfig.OutboundConfig(80, 32));
        assertThat(ex.getMessage()).contains("paralife.runtime.app.outbound.frame-size-budget-bytes");
    }

    @Test
    void rejectsEncodeBatchTooSmall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AppRuntimeConfig.EncodeConfig(-1, 0));
        assertThat(ex.getMessage()).contains("paralife.runtime.app.encode.encode-batch-hint");
    }

    @Test
    void parallelEncodeSentinelDisabledIsAllowed() {
        AppRuntimeConfig.EncodeConfig e = new AppRuntimeConfig.EncodeConfig(-1, 8);
        assertThat(e.parallelEncodeThreshold()).isEqualTo(-1);
    }

    @Test
    void parallelEncodePositiveReservationAllowed() {
        AppRuntimeConfig.EncodeConfig e = new AppRuntimeConfig.EncodeConfig(64, 8);
        assertThat(e.parallelEncodeThreshold()).isEqualTo(64);
    }

    /**
     * D-20 invariant: AppRuntimeConfig MUST NOT shadow
     * paralife.admission.backpressure.outbound-queue-size. AdmissionConfig.defaults()
     * is independent of AppRuntimeConfig and equals 128.
     */
    @Test
    void d20AlongsideNotMove_admissionBackpressureUntouched() {
        AdmissionConfig admission = AdmissionConfig.defaults();
        assertThat(admission.backpressure().outboundQueueSize()).isEqualTo(128);
    }

    @SpringBootTest(classes = AppRuntimeConfigTest.TestApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties = {
            "paralife.tick.auto-start=false",
            "paralife.runtime.app.outbound.queue-watermark-pct=70",
            "paralife.runtime.app.encode.parallel-encode-threshold=64"
    })
    static class BindingRoundTrip {
        @Autowired AppRuntimeConfig cfg;

        @Test
        void overridesAndDefaultsCoexistInNestedRecords() {
            assertThat(cfg.outbound().queueWatermarkPct()).isEqualTo(70);
            assertThat(cfg.encode().parallelEncodeThreshold()).isEqualTo(64);
            // defaults preserved on un-overridden fields
            assertThat(cfg.outbound().frameSizeBudgetBytes()).isEqualTo(1024);
            assertThat(cfg.encode().encodeBatchHint()).isEqualTo(8);
        }
    }

    @Configuration
    @EnableConfigurationProperties(AppRuntimeConfig.class)
    static class TestApp {}
}
