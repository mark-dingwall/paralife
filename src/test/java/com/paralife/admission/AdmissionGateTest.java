package com.paralife.admission;

import com.paralife.websocket.RespawnConfig;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdmissionGateTest {

    private AdmissionConfig cfg;
    private RespawnConfig respawnCfg;
    private WorldGrid worldGrid;
    private TickHealthMonitor tickHealth;
    private ResumeTokenRegistry resumeRegistry;
    private SimpleMeterRegistry registry;
    private AdmissionMetrics metrics;
    private AdmissionGate gate;

    @BeforeEach
    void setup() {
        cfg = new AdmissionConfig(2, false,
                AdmissionConfig.TickOverloadConfig.defaults(),
                AdmissionConfig.BackpressureConfig.defaults(),
                AdmissionConfig.AttributionConfig.defaults());
        respawnCfg = new RespawnConfig(3);
        worldGrid = Mockito.mock(WorldGrid.class);
        tickHealth = Mockito.mock(TickHealthMonitor.class);
        resumeRegistry = Mockito.mock(ResumeTokenRegistry.class);
        registry = new SimpleMeterRegistry();
        metrics = new AdmissionMetrics(registry);
        gate = new AdmissionGate(cfg, respawnCfg, worldGrid, tickHealth, resumeRegistry, metrics);

        when(tickHealth.isOverloaded()).thenReturn(false);
        when(worldGrid.livingEntityCount()).thenReturn(0);
        when(resumeRegistry.tryRebind(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
                .thenReturn(Optional.empty());
    }

    private AdmissionGate.AdmissionRequest req(boolean alive, boolean isRespawn, int respawnCount, Optional<String> token) {
        return new AdmissionGate.AdmissionRequest("session-A", 100L, alive, isRespawn, respawnCount, token);
    }

    @Test
    void allowWhenAllGuardsPass() {
        when(worldGrid.livingEntityCount()).thenReturn(0);
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(r).isInstanceOf(AdmissionResult.Allow.class);
    }

    @Test
    void rejectsMaintenanceFirst() {
        cfg = new AdmissionConfig(2, true, cfg.tickOverload(), cfg.backpressure(), cfg.attribution());
        gate = new AdmissionGate(cfg, respawnCfg, worldGrid, tickHealth, resumeRegistry, metrics);
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(r).isInstanceOf(AdmissionResult.Reject.class);
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.MAINTENANCE);
    }

    @Test
    void rejectsTickOverloadAheadOfCap() {
        when(tickHealth.isOverloaded()).thenReturn(true);
        when(worldGrid.livingEntityCount()).thenReturn(99);
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.TICK_OVERLOAD);
    }

    @Test
    void rejectsAlreadyRegisteredBeforeResumeToken() {
        // CORRECTED guard order (codex MEDIUM): a live session sending r|<type>|<token>
        // gets 409, NOT a rebind. tryRebind must NOT be called.
        AdmissionResult r = gate.evaluate(req(true, false, 0, Optional.of("r:deadbeefcafe1234")));
        assertThat(r).isInstanceOf(AdmissionResult.Reject.class);
        assertThat(((AdmissionResult.Reject) r).code()).isEqualTo(409);
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.ALREADY_REGISTERED);
        verify(resumeRegistry, never()).tryRebind(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
    }

    @Test
    void rejectsAlreadyRegistered() {
        AdmissionResult r = gate.evaluate(req(true, false, 0, Optional.empty()));
        assertThat(((AdmissionResult.Reject) r).code()).isEqualTo(409);
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.ALREADY_REGISTERED);
    }

    @Test
    void rejectsWorldFull() {
        // Fill reserved slots up to cap (2) via legitimate Allow evaluations.
        // Phase 17 hardening: AdmissionGate uses an atomic reservation counter
        // (not livingEntityCount) for cap admission decisions.
        assertThat(gate.evaluate(req(false, false, 0, Optional.empty()))).isInstanceOf(AdmissionResult.Allow.class);
        assertThat(gate.evaluate(req(false, false, 0, Optional.empty()))).isInstanceOf(AdmissionResult.Allow.class);
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.WORLD_FULL);
    }

    @Test
    void rejectsRespawnCap() {
        when(worldGrid.livingEntityCount()).thenReturn(0);
        AdmissionResult r = gate.evaluate(req(false, true, 3, Optional.empty()));
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.RESPAWN_CAP);
    }

    @Test
    void respawnCapDoesNotApplyOnFreshRegistration() {
        when(worldGrid.livingEntityCount()).thenReturn(0);
        AdmissionResult r = gate.evaluate(req(false, false, 999, Optional.empty()));
        assertThat(r).isInstanceOf(AdmissionResult.Allow.class);
    }

    @Test
    void rebindOnValidResumeToken() {
        when(resumeRegistry.tryRebind("r:deadbeefcafe1234", "session-A", 100L))
                .thenReturn(Optional.of(new ResumeTokenRegistry.RebindOutcome("entity-old", "r:freshtoken00000")));
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.of("r:deadbeefcafe1234")));
        assertThat(r).isInstanceOf(AdmissionResult.Rebind.class);
        AdmissionResult.Rebind rb = (AdmissionResult.Rebind) r;
        assertThat(rb.entityId()).isEqualTo("entity-old");
        assertThat(rb.freshResumeToken()).isEqualTo("r:freshtoken00000");
    }

    @Test
    void unknownResumeTokenFallsThroughToFreshRegistration() {
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.of("r:unknowntoken000")));
        assertThat(r).isInstanceOf(AdmissionResult.Allow.class);
    }

    @Test
    void rebindBypassesWorldFull() {
        when(worldGrid.livingEntityCount()).thenReturn(2);
        when(resumeRegistry.tryRebind("r:deadbeefcafe1234", "session-A", 100L))
                .thenReturn(Optional.of(new ResumeTokenRegistry.RebindOutcome("entity-old", "r:freshtoken00000")));
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.of("r:deadbeefcafe1234")));
        assertThat(r).isInstanceOf(AdmissionResult.Rebind.class);
    }

    @Test
    void counterIncrementsOnEachRejection() {
        cfg = new AdmissionConfig(2, true, cfg.tickOverload(), cfg.backpressure(), cfg.attribution());
        gate = new AdmissionGate(cfg, respawnCfg, worldGrid, tickHealth, resumeRegistry, metrics);
        gate.evaluate(req(false, false, 0, Optional.empty()));
        gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(registry.counter(AdmissionMetrics.M_REJECTED, "reason", RejectionToken.MAINTENANCE).count())
                .isEqualTo(2.0);
    }
}
