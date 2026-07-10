package com.paralife.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paralife.websocket.RespawnConfig;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        com.paralife.engine.TickEngine mockTickEngine = Mockito.mock(com.paralife.engine.TickEngine.class);
        when(mockTickEngine.currentTick()).thenReturn(0L);
        AttributionTagger tagger = new AttributionTagger(64, mockTickEngine);
        metrics = new AdmissionMetrics(registry, cfg, mockTickEngine, tagger);
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

    // --- Precedence: cap-armed edges. Global cap (guard 5) is the LOWEST-precedence reject;
    //     maintenance (1), tick-overload (2), and a valid rebind (4) must all win over a reached
    //     cap. These arm the reservation counter via seedReservedSlots() — the production
    //     @PostConstruct seed path, which does NOT fire in unit tests — because without arming, the
    //     cap guard is inert and the precedence assertions pass vacuously (ADMISSION.md §0 A25–A27
    //     + guard-order note). ---

    /**
     * Positive control: with the reservation counter seeded to the cap, a plain registration is
     * rejected WORLD_FULL. Proves the seed-arming genuinely trips the cap guard, so the three
     * precedence tests below cannot be vacuously green.
     */
    @Test
    void seededCapAloneRejectsWorldFull() {
        when(worldGrid.livingEntityCount()).thenReturn(cfg.cap());
        gate.seedReservedSlots();
        assertThat(gate.reservedSlots()).isEqualTo(cfg.cap());
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.WORLD_FULL);
    }

    @Test
    void maintenanceRejectedEvenWhenOverloaded() {
        cfg = new AdmissionConfig(2, true, cfg.tickOverload(), cfg.backpressure(), cfg.attribution());
        gate = new AdmissionGate(cfg, respawnCfg, worldGrid, tickHealth, resumeRegistry, metrics);
        when(tickHealth.isOverloaded()).thenReturn(true);
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(((AdmissionResult.Reject) r).token())
                .as("maintenance (guard 1) must win over tick-overload (guard 2)")
                .isEqualTo(RejectionToken.MAINTENANCE);
    }

    @Test
    void maintenanceRejectedEvenWhenCapReached() {
        cfg = new AdmissionConfig(2, true, cfg.tickOverload(), cfg.backpressure(), cfg.attribution());
        gate = new AdmissionGate(cfg, respawnCfg, worldGrid, tickHealth, resumeRegistry, metrics);
        when(worldGrid.livingEntityCount()).thenReturn(cfg.cap());
        gate.seedReservedSlots();
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(((AdmissionResult.Reject) r).token())
                .as("maintenance (guard 1) must win over a reached cap (guard 5)")
                .isEqualTo(RejectionToken.MAINTENANCE);
    }

    @Test
    void tickOverloadRejectedEvenWhenCapReached() {
        when(tickHealth.isOverloaded()).thenReturn(true);
        when(worldGrid.livingEntityCount()).thenReturn(cfg.cap());
        gate.seedReservedSlots();
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.empty()));
        assertThat(((AdmissionResult.Reject) r).token())
                .as("tick-overload (guard 2) must win over a reached cap (guard 5)")
                .isEqualTo(RejectionToken.TICK_OVERLOAD);
    }

    @Test
    void validRebindWinsOverReachedCap() {
        when(resumeRegistry.tryRebind(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
                .thenReturn(Optional.of(new ResumeTokenRegistry.RebindOutcome("entity-X", "r:0000feedface0001")));
        when(worldGrid.livingEntityCount()).thenReturn(cfg.cap());
        gate.seedReservedSlots();
        AdmissionResult r = gate.evaluate(req(false, false, 0, Optional.of("r:0000deadbeef0001")));
        assertThat(r)
                .as("a valid STALLED rebind (guard 4) must win over a reached cap (guard 5)")
                .isInstanceOf(AdmissionResult.Rebind.class);
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
        // Phase 18: rejected counter now includes source tag (D-12); back-compat shim uses source=unknown.
        assertThat(registry.counter(AdmissionMetrics.M_REJECTED,
                "reason", RejectionToken.MAINTENANCE, "source", "unknown").count())
                .isEqualTo(2.0);
    }
}
