package com.paralife.admission;

import com.paralife.engine.TickConfig;
import com.paralife.engine.TickEngine;
import com.paralife.engine.TickEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class TickHealthMonitorTest {

    private TickEngine tickEngine;
    private TickConfig tickConfig;
    private AdmissionConfig admissionConfig;
    private AdmissionMetrics metrics;
    private TickHealthMonitor monitor;

    @BeforeEach
    void setup() {
        tickEngine = Mockito.mock(TickEngine.class);
        tickConfig = Mockito.mock(TickConfig.class);
        when(tickConfig.intervalMs()).thenReturn(100L);  // 100ms budget
        admissionConfig = new AdmissionConfig(
                256, false,
                new AdmissionConfig.TickOverloadConfig(80, 60, 5),  // window=5
                AdmissionConfig.BackpressureConfig.defaults(),
                AdmissionConfig.AttributionConfig.defaults());
        when(tickEngine.currentTick()).thenReturn(0L);
        AttributionTagger tagger = new AttributionTagger(64, tickEngine);
        metrics = new AdmissionMetrics(new SimpleMeterRegistry(), admissionConfig, tickEngine, tagger);
        monitor = new TickHealthMonitor(tickEngine, admissionConfig, tickConfig, metrics);
    }

    private void tick(long workMs, long tickNumber) {
        when(tickEngine.getLastTickWorkMs()).thenReturn(workMs);
        monitor.onTick(new TickEvent(tickNumber));
    }

    @Test
    void initiallyNotOverloaded() {
        assertThat(monitor.isOverloaded()).isFalse();
    }

    @Test
    void singleSpikeBeforeWindowFillsCannotTriggerOverload() {
        // window=5; push ONE 1000ms spike. With the window-fill guard, the gate cannot trip.
        tick(1000, 1);
        assertThat(monitor.isOverloaded()).isFalse();
        assertThat(monitor.filledCount()).isEqualTo(1);
    }

    @Test
    void warmupSamplesNeverTriggerOverload() {
        // window=5; push 4 high samples (still < window.length). No overload.
        for (int i = 1; i <= 4; i++) tick(500, i);
        assertThat(monitor.isOverloaded()).isFalse();
        assertThat(monitor.filledCount()).isEqualTo(4);
        // Fifth sample completes the window; THEN gate evaluates.
        tick(500, 5);
        assertThat(monitor.filledCount()).isEqualTo(5);
        assertThat(monitor.isOverloaded()).isTrue();
    }

    @Test
    void overloadFiresWhenRollingMeanExceedsHighWatermark() {
        // Pre-fill: 5 samples at 50ms (mean=50 < 80)
        for (int i = 1; i <= 5; i++) tick(50, i);
        assertThat(monitor.isOverloaded()).isFalse();
        // Push high samples until mean > 80
        tick(200, 6);   // mean = (50*4 + 200)/5 = 80, NOT > 80
        assertThat(monitor.isOverloaded()).isFalse();
        tick(200, 7);   // mean = (50*3 + 200*2)/5 = 110, > 80 → trigger
        assertThat(monitor.isOverloaded()).isTrue();
    }

    @Test
    void hysteresisPreventsImmediateRecovery() {
        for (int i = 1; i <= 5; i++) tick(200, i);
        assertThat(monitor.isOverloaded()).isTrue();
        tick(70, 6);   // mean=174 > 60
        assertThat(monitor.isOverloaded()).isTrue();
        tick(50, 7);   // mean=144 > 60
        assertThat(monitor.isOverloaded()).isTrue();
    }

    @Test
    void recoversWhenRollingMeanDropsBelowLowWatermark() {
        for (int i = 1; i <= 5; i++) tick(200, i);
        assertThat(monitor.isOverloaded()).isTrue();
        for (int i = 6; i <= 10; i++) tick(30, i);
        assertThat(monitor.isOverloaded()).isFalse();
    }

    @Test
    void noFlappingOnSamplesInBetweenWatermarks() {
        for (int i = 1; i <= 5; i++) tick(200, i);
        assertThat(monitor.isOverloaded()).isTrue();
        for (int i = 6; i <= 10; i++) tick(70, i);    // mean=70, between 60 and 80
        assertThat(monitor.isOverloaded()).isTrue();
    }

    @Test
    void rollingWindowOverwritesOldestSample() {
        for (int i = 1; i <= 5; i++) tick(100, i);   // mean=100 → overload
        assertThat(monitor.isOverloaded()).isTrue();
        tick(0, 6);   // window=[0,100,100,100,100], mean=80; not < 60, still overloaded
        tick(0, 7);   // window=[0,0,100,100,100], mean=60; not < 60 (strict), still overloaded
        assertThat(monitor.isOverloaded()).isTrue();
        tick(0, 8);   // window=[0,0,0,100,100], mean=40, < 60 → recover
        assertThat(monitor.isOverloaded()).isFalse();
    }

    @Test
    void gaugeUpdatedDuringWarmupEvenWhenGateDeferred() {
        SimpleMeterRegistry meterReg = new SimpleMeterRegistry();
        AdmissionMetrics m = new AdmissionMetrics(meterReg);
        TickHealthMonitor mon = new TickHealthMonitor(tickEngine, admissionConfig, tickConfig, m);
        when(tickEngine.getLastTickWorkMs()).thenReturn(123L);
        mon.onTick(new TickEvent(1));
        assertThat(meterReg.get(AdmissionMetrics.M_TICK_WORK_MS).gauge().value()).isEqualTo(123.0);
        assertThat(mon.isOverloaded()).isFalse();   // warm-up; gate deferred
    }
}
