package com.paralife.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paralife.engine.TickEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DeathDiagnosticsTest {

    private TickEngine tickEngine;
    private SimpleMeterRegistry meterRegistry;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private DeathDiagnostics diagnostics;

    @BeforeEach
    void setUp() {
        tickEngine = mock(TickEngine.class);
        meterRegistry = new SimpleMeterRegistry();
        logger = (Logger) LoggerFactory.getLogger(DeathDiagnostics.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        diagnostics = new DeathDiagnostics(tickEngine, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        meterRegistry.close();
    }

    @Test
    void firstLethalHintWinsLifecycleRecordAndMeterTags() {
        when(tickEngine.currentTick()).thenReturn(11L, 19L);

        diagnostics.recordBirth("p-1");
        diagnostics.hintLethal("p-1", DeathDiagnostics.Cause.COMBAT, 37);
        diagnostics.hintLethal("p-1", DeathDiagnostics.Cause.TOXIN, 4);
        diagnostics.recordDeath("p-1", "CATALYST");

        assertThat(lifecycleMessages()).containsExactly(
                "DEATH-TRACE id=p-1 type=CATALYST cause=COMBAT "
                        + "lifespanTicks=8 preHitEnergy=37 deathTick=19");
        assertThat(meterRegistry.find("paralife.diag.deaths")
                .tags("cause", "combat", "type", "CATALYST")
                .counter()).isNotNull();
    }

    @Test
    void missingLethalHintFallsBackToStarvation() {
        when(tickEngine.currentTick()).thenReturn(46L);

        diagnostics.recordDeath("p-2", "MEMBRANE");

        assertThat(lifecycleMessages()).containsExactly(
                "DEATH-TRACE id=p-2 type=MEMBRANE cause=STARVATION "
                        + "lifespanTicks=-1 preHitEnergy=null deathTick=46");
        assertThat(meterRegistry.find("paralife.diag.deaths")
                .tags("cause", "starvation", "type", "MEMBRANE")
                .counter()).isNotNull();
    }

    @Test
    void forgetAllowsReusedIdToStartFreshLifecycle() {
        when(tickEngine.currentTick()).thenReturn(2L, 20L, 25L);

        diagnostics.recordBirth("reused");
        diagnostics.hintLethal("reused", DeathDiagnostics.Cause.OVERCROWDING, 12);
        diagnostics.forget("reused");

        assertThat(lifecycleMessages()).isEmpty();

        diagnostics.recordBirth("reused");
        diagnostics.recordDeath("reused", "SPORE");

        assertThat(lifecycleMessages()).containsExactly(
                "DEATH-TRACE id=reused type=SPORE cause=STARVATION "
                        + "lifespanTicks=5 preHitEnergy=null deathTick=25");
        assertThat(meterRegistry.find("paralife.diag.deaths")
                .tags("cause", "starvation", "type", "SPORE")
                .counter()).isNotNull();
    }

    private List<String> lifecycleMessages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("DEATH-TRACE"))
                .toList();
    }
}
