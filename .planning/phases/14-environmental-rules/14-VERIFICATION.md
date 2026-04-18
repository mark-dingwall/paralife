---
phase: 14-environmental-rules
verified: 2026-04-17T16:25:00Z
reverified: 2026-04-18T00:55:00Z
status: passed
score: 5/5 success criteria verified (1 override applied for R13)
overrides_applied: 1
overrides:
  - must_have: "Environmental effects use Cell flags system (extending FLAG_OVERCROWDED pattern)"
    reason: |
      R13's spirit — "extensible bit-based env state visible to perception" — IS delivered,
      via a richer three-layer projection model than the literal wording anticipated. The
      actual design (decided in cycle-6 MEDIUM #8, documented in every plan SUMMARY, and
      now recorded in CLAUDE.md > Architecture > "Env state projection"):

        Layer 1 — Shadow grids (byte[][] toxinGrid, mutagenGrid on EnvironmentEngine,
                  intensity 0–255): authoritative effect state. Cell.flags cannot host
                  these because a single bit can't represent 0–255 intensity.

        Layer 2 — Bitmask cache (Map<Position,Byte> cellStatusCache + Map<String,Byte>
                  entityStatusCache, rebuilt in EnvironmentEngine.buildStatusCaches()
                  each tick per D-41): read-only projection of Layer 1 through thresholds
                  into D-38 / D-39 bits (TOXIN_PRESENT=0x02, MUTAGEN_ZONE=0x04; TOXIC=0x02,
                  MUTATING=0x04, BUFFED=0x08). O(1) lookup during perception assembly.
                  This IS the "Cell flags system" in spirit — a read-only bitmask keyed
                  by Position, co-located with the shadow grids that produce it.

        Layer 3 — Wire bitmask (Messages.CellView.cellStatus + entityStatus bytes,
                  emitted per bot by PerceptionBroadcaster.cellToView): zero-trust
                  vision-scoped projection of Layer 2. Verbatim recomposition
                  `cellStatus = (cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit`
                  redacts OVERCROWDED to bot's 5x5 Moore count so outer-vision cells
                  correctly under-report global overcrowding (D-40 incomplete-information
                  design). OVERCROWDED lives dually — globally on Cell.FLAG_OVERCROWDED
                  for server-side penalty logic AND vision-scoped in cellStatus bit 0 for
                  perception. STARVING remains on Cell.FLAG_STARVING, read directly by
                  HeuristicBrain (entity-intrinsic state not subject to spatial redaction).

      Why not literal Cell.flags extension:
        (a) intensity-valued effects (toxin 0–255) cannot fit in single bits,
        (b) cache locality + additive evolution per D-31 / D-33 favour per-effect
            shadow grids over a shared flag word,
        (c) composite SENSOR stitched coverage needs a per-bot projection surface
            Cell.flags cannot emulate,
        (d) vision-scoped OVERCROWDED redaction requires per-bot recomposition
            over a derived cache, not direct read of a global flag bit.

      D-38 / D-39 bit layout is NOW correctly implemented after the bit-0 collision
      fix (commit dc70527) that moved CELL_STATUS_TOXIN_PRESENT and ENTITY_STATUS_TOXIC
      from 0x01 (colliding with OVERCROWDED / STARVING) to 0x02 per D-38 / D-39 spec.
      Ambient toxin signal now reaches client wire format.

      Three-layer model documented in CLAUDE.md (Architecture section) and in
      Messages.CellView Javadoc (bit-layout tables + pipeline summary) so future
      contributors cannot conflate the surfaces.
    accepted_by: "mark-dingwall"
    accepted_at: "2026-04-18T00:55:00Z"
deferred: []
---

# Phase 14: Environmental Rules — Verification Report

**Phase Goal:** Four spatially-propagating environmental effects (toxin, mutagen, lightning, compost) stressing the Phase 13 metabolism system, with seasonal Poisson triggering, CA shadow grids, mutagen survivor buffs, and perception-visible status bytes.

**Verified:** 2026-04-17T16:25:00Z
**Re-verified:** 2026-04-18T00:55:00Z (R13 override accepted; bit-0 collision fixed; three-layer docs added)
**Status:** passed (5/5, 1 override applied)

---

## Goal Achievement

### Observable Truths (against ROADMAP.md Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | At least two new environmental effects beyond overcrowding | VERIFIED | Four effects delivered — toxin, mutagen, lightning, compost — each with spawn/advance/resolve pipeline in `EnvironmentEngine.onTick` (lines 248-265) |
| 2 | Environmental effects use Cell flags system (extending FLAG_OVERCROWDED pattern) | OVERRIDE (spirit delivered via 3-layer projection) | Three-layer model: (L1) shadow grids (byte[][] toxinGrid, mutagenGrid; intensity 0–255), (L2) read-only bitmask caches Map<Position,Byte> cellStatusCache + Map<String,Byte> entityStatusCache rebuilt each tick in EnvironmentEngine.buildStatusCaches() per D-41, (L3) per-bot wire bitmask Messages.CellView.cellStatus/entityStatus with OVERCROWDED redacted to bot vision per D-40. D-38 bits: OVERCROWDED=0, TOXIN_PRESENT=0x02, MUTAGEN_ZONE=0x04 (bit-0 collision fixed in commit dc70527). Documented in CLAUDE.md Architecture section + Messages.CellView Javadoc. Cell.FLAG_OVERCROWDED retained server-global; Cell.FLAG_STARVING unchanged. |
| 3 | Spatial propagation of effects across ticks (not just local) | VERIFIED | `ToxinPathGenerator` generates Catmull-Rom spline paths (src/main/java/com/paralife/engine/ToxinPathGenerator.java:159 `catmullRom()`); `CellularAutomaton.diffuseStep()` performs double-buffered Moore-neighbourhood diffusion with decay (src/main/java/com/paralife/engine/CellularAutomaton.java:38); mutagen strain gossip propagates to 8 neighbours per tick (EnvironmentEngine.advanceMutagen at line 431); lightning dual-radius Euclidean scan |
| 4 | Configurable parameters in application.yml | VERIFIED | `paralife.simulation.events.{lightning,toxin,mutagen,compost}` block at application.yml:91-137 — all 4 effects have tunable params (peak-lambda, off-season-lambda, damage, radii, duration, resistance, etc.) |
| 5 | Unit tests for each environmental effect | VERIFIED | ToxinTest (21 @Test methods), MutagenTest (23 @Test), LightningTest (12 @Test), CompostTest (2 @Test), plus CellularAutomatonTest (10), ToxinPathGeneratorTest (9), SeasonalPoissonTest (3), EnvironmentDeterminismTest (2), EnvironmentFullStackSmokeTest (1), EnvironmentPhaseGateIntegrationTest (1) |

**Score:** 5/5 — 4 verified literally + 1 accepted override for R13 (three-layer projection delivers R13 spirit; bit-0 collision fixed in dc70527; docs updated). See frontmatter `overrides` block for full rationale.

---

### Required Artifacts

All artifacts declared in the 6 PLAN frontmatters were verified to exist and be substantive.

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/paralife/engine/EnvironmentEngine.java` | @Component @Order(14) tick listener with 4 effect pipelines | VERIFIED | 1401 LOC; @Component at :60; @Order(TICK_ORDER=14) at :234; onTick at :235 wires toxin → mutagen → lightning → buildStatusCaches → processEnvDeaths |
| `src/main/java/com/paralife/engine/EnvironmentConfig.java` | @ConfigurationProperties record tree for lightning/toxin/mutagen/compost + nullable Long seed | VERIFIED | 266 LOC; `Long seed` at line 22 (cycle-6 HIGH #1) |
| `src/main/java/com/paralife/engine/BuffRegistry.java` | Shadow registry for mutagen survivor buffs | VERIFIED | 164 LOC; @Component; grant/getBuffs/hasBuff/expireBuffs/unregisterEntity/clear + transferBuffs (cycle-9 B.2) + getRegisteredEntityIds |
| `src/main/java/com/paralife/engine/DeathCleanupHooks.java` | Interface: clearInfectionOnDeath, applyCompost, transferMutagenState | VERIFIED | Present; extended in Plan 14-03 |
| `src/main/java/com/paralife/engine/EnvCleanupHooksBean.java` | @Component impl owning infections/cureImmuneUntil/pendingBuffGrants maps + fail-fast listener | VERIFIED | Implements DeathCleanupHooks + ApplicationListener<ContextRefreshedEvent>; typed containers Map<String,Infection> + List<PendingGrant> |
| `src/main/java/com/paralife/engine/DeathFinalizer.java` | @Component centralising particle/bondedPair/compositeMember death cleanup | VERIFIED | 169 LOC; finalizeParticleDeath / finalizeBondedPairDeath / finalizeCompositeMemberDeath + deathEventCount (Plan 14-06) |
| `src/main/java/com/paralife/engine/EnvPostActionReconciler.java` | @Component @Order(25) between ActionResolver and PerceptionBroadcaster | VERIFIED | TICK_ORDER=25 at :32; @Order(TICK_ORDER) at :41; onTick calls processEnvDeaths + drainPostActionGrants(event.tickNumber()) |
| `src/main/java/com/paralife/engine/ToxinPathGenerator.java` | Catmull-Rom spline generator with pinned constructor pair | VERIFIED | `catmullRom()` at :159; `generatePath()` at :52; public no-arg ctor + package-private Random overload |
| `src/main/java/com/paralife/engine/ToxinEvent.java` | Immutable record (spawnTick, lifetimeTicks, prePath, headIdx, seed) | VERIFIED | Record present with withHeadIdx / hasReachedEnd / isExpired |
| `src/main/java/com/paralife/engine/CellularAutomaton.java` | Double-buffered diffusion + decay step helper | VERIFIED | `public static int diffuseStep(...)` returning non-zero cell count |
| `src/main/java/com/paralife/engine/MutagenEvent.java` | Immutable record (spawnTick, originCell, lifetimeTicks) | VERIFIED | Record present |
| `src/main/java/com/paralife/engine/Infection.java` | Immutable record with Position field (T-14-03-11) | VERIFIED | Record with initialTicks/strain/damagePerTick/ticksLeft/position |
| `src/main/java/com/paralife/engine/EntityIds.java` | Static entityIdOf helper | VERIFIED | Used by EnvironmentEngine.buildStatusCaches |
| `src/main/java/com/paralife/websocket/Messages.java` | CellView record with byte cellStatus + byte entityStatus | VERIFIED | Lines 230-231 declare `byte cellStatus, byte entityStatus`; 3/4/6-arg constructors preserved |
| `src/main/resources/application.yml` | paralife.simulation.events.{lightning,toxin,mutagen,compost} | VERIFIED | Lines 91-137; all 4 sections populated; seed key omitted (cycle-6 HIGH #1) |

**All new classes (production + test): 27 files created, all compile and pass tests.**

---

### Key Link Verification (Wiring)

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| Spring context → EnvironmentEngine | tick pipeline | @Component + @EventListener @Order(14) | WIRED | Participates in TickEvent pipeline; env damage runs before ActionResolver(@Order 20) |
| SimulationEngine → DeathFinalizer | per-death cleanup | @Autowired @Lazy | WIRED | Phase 3a solo-death loop routes through DeathFinalizer.finalizeParticleDeath / finalizeBondedPairDeath; composite path via handleMemberDeath callback |
| DeathFinalizer → EnvCleanupHooksBean | clearInfectionOnDeath / applyCompost | DeathCleanupHooks interface | WIRED | 3 clearInfectionOnDeath calls per bondedPair (primary, secondary, bp.id()) per cycle-4 action item #6 |
| EnvironmentEngine → EnvCleanupHooksBean | compost write | CompostSink setter post-@PostConstruct | WIRED | @PostConstruct registers EnvironmentEngine as the sink; fail-fast listener at context refresh throws if null (cycle-9 A) |
| EnvironmentEngine → DeathFinalizer | processEnvDeaths routes composite-member env-deaths | direct call | WIRED | Same 97/3 roll fires same tick for env-killed composite members |
| EnvPostActionReconciler → EnvironmentEngine | processEnvDeaths + drainPostActionGrants(tickNumber) | direct call | WIRED | @Order(25) slot; finalizes ActionResolver composite-attack splash kills same tick |
| PerceptionBroadcaster → EnvironmentEngine | cellStatus / entityStatus projection | getCellStatus / getEntityStatus | WIRED | PerceptionBroadcaster.java:391 `environmentEngine.getCellStatus(cellPos)` + :400 `environmentEngine.getEntityStatus(occupantId)` |
| HeuristicBrain → cellStatus / entityStatus | bot decision-making | observable-only bits | WIRED | Reads STARVING/TOXIC/MUTATING/BUFFED bits; priority-weighted prey selection; TOXIC avoidance when energy < 30% |
| ActionResolver → EnvironmentEngine | splash damage in resolveAttackerAttack | setter-injected @Lazy | WIRED | ActionResolver.java:645 computeSplashDamage + :655 markEnvDamageApplied |
| SimulationEngine → BuffRegistry | ATTACK_PLUS_1 / UPKEEP_MINUS_1 effect application | @Autowired | WIRED | Solo particle applyAttackBoost + Particle/BondedPair processEnergyDecay |
| CompositeEnergyDistributor → BuffRegistry | UPKEEP_MINUS_1 per-member reduction | setter-injected | WIRED | processCompositeEnergy reduces passiveDrain by 1 floored at 0 |

All critical wiring is in place. No ORPHANED components.

---

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| EnvironmentEngine.onTick | toxinGrid, mutagenGrid, activeToxin, activeMutagen, lightningStrikeCount | spawnToxin/spawnMutagen/spawnLightning driven by seasonalXLambda + rng.nextDouble | Yes — shadow grids populate real bytes from spline-stamped and CA-diffused intensities | FLOWING |
| EnvironmentEngine.buildStatusCaches | cellStatusCache, entityStatusCache | Reads real shadow grid + BuffRegistry + Cell.flags | Yes — status bits derived from real intensities (TOXIN_PRESENT ≥ threshold, MUTATING for infected IDs) | FLOWING |
| PerceptionBroadcaster.cellToView | byte cellStatus, byte entityStatus | environmentEngine.getCellStatus/getEntityStatus + per-bot overcrowded recomposition | Yes — cascaded from shadow grids via EnvironmentEngine | FLOWING |
| Messages.CellView (broadcast) | cellStatus, entityStatus | Jackson serialises 6-field record to WebSocket | Yes — smoke test proves bot receives non-zero status byte when toxin stamped near vision | FLOWING (proven by EnvironmentFullStackSmokeTest) |
| BuffRegistry | ConcurrentHashMap<String, CopyOnWriteArrayList<ActiveBuff>> | grantSurvivorBuffs on infection expiry | Yes — phase-gate test asserts buff-holding entities > 0 after 300 ticks | FLOWING |
| Cell.nutrientLevel (compost) | Cell.nutrientLevel (via withNutrientLevel) | applyCompost writes full=30 at death cell + half=15 at 8 neighbours | Yes — CompostTest verifies exact values with clamp to FertilityConfig.maxLevel | FLOWING |

**No HOLLOW or DISCONNECTED artifacts. Full end-to-end flow proven.**

---

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full test suite passes | `./gradlew test --rerun-tasks` | 570 tests, 0 failures, 3 ignored (documented @Disabled perf/e2e placeholders), BUILD SUCCESSFUL in 2m 1s | PASS |
| JaCoCo report generated | (part of `./gradlew test`) | Reports written to build/reports/tests/test/ | PASS |
| Spring context starts | Invoked indirectly by every @SpringBootTest | No ERROR lines; startup logged "Started ParalifeApplication" across multiple test runs | PASS |
| Phase 14 @Disabled count matches baseline | `grep -c "@Disabled" src/test/java/com/paralife/engine/*.java` | 3 @Disabled (2 in ToxinTest e2e placeholders, 1 in CellularAutomatonTest perf-only smoke) | PASS — matches "3 ignored" in report |
| EnvironmentPhaseGateIntegrationTest (ROADMAP-literal gate) | `./gradlew test --tests "*EnvironmentPhaseGateIntegrationTest"` | Included in full suite pass (1/1) | PASS |
| EnvironmentDeterminismTest (cross-run equality) | `./gradlew test --tests "*EnvironmentDeterminismTest"` | Included in full suite pass (2/2) | PASS |
| EnvironmentFullStackSmokeTest (WebSocket wire-path) | `./gradlew test --tests "*EnvironmentFullStackSmokeTest"` | Included in full suite pass (1/1) | PASS |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| R12 | 01, 02, 03, 05, 06 | At least two new environmental effects beyond overcrowding | SATISFIED | 4 effects shipped (toxin, mutagen, lightning, compost) — 2x the minimum |
| R13 | 01, 02, 03, 05, 06 | Environmental effects use Cell flags system (extending FLAG_OVERCROWDED pattern) | DEVIATION (override suggested) | Implementation uses shadow grids + Messages.CellView byte projection. `Cell.flags` unchanged (FLAG_OVERCROWDED + FLAG_STARVING only). All 6 plan summaries document the design choice with cycle-6 MEDIUM #8 authoritative rationale. |
| R14 | 01, 02, 03, 04, 05, 06 | Spatial propagation of effects across ticks | SATISFIED | ToxinPathGenerator (spline) + CellularAutomaton (double-buffered diffusion) + mutagen strain gossip (8-neighbour stochastic) + lightning dual-radius. Phase-gate test asserts all 4 counters > 0 at tick 300. |

**No ORPHANED requirements:** REQUIREMENTS.md maps only R12/R13/R14 to Phase 14, and all three appear in plan frontmatters.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| src/main/java/com/paralife/engine/EnvCleanupHooksBean.java | :31 | "placeholders" in Javadoc | Info | Comment describes Plan 01 typed-container migration; NOT a code stub |
| src/test/java/com/paralife/engine/ToxinTest.java | :351 | 2 @Disabled methods (composite_splashKillFinalizedSameTickViaReconciler, splashAppliesInCompositeViaActionResolverResolveAttackerAttack) | Info | Documented as e2e integration placeholders deferred to Plan 06; source greps in Plan 14-02 confirm wiring in ActionResolver |
| src/test/java/com/paralife/engine/CellularAutomatonTest.java | — | 1 @Disabled("perf-only") smoke method | Info | diffusionCostOn256x256For60TicksWithinLooseBound is a conditional perf probe, not a correctness test |

No BLOCKER anti-patterns. No TODO/FIXME/HACK/PLACEHOLDER in production code.

---

### Human Verification Required

None. All PLAN-documented `<manual_verifications>` items (visual plausibility of toxin path, subjective event-frequency balance) are explicitly flagged as aesthetic/subjective and are outside the phase-gate. The automated phase-gate (`EnvironmentPhaseGateIntegrationTest`) covers the ROADMAP-literal 300-tick full-pipeline contract.

---

## Gaps Summary

**Resolved 2026-04-18:** The single surfaced gap (R13 literal wording) was reframed as a three-layer projection model and an override was applied. See frontmatter `overrides` block for the accepted rationale. Two remediation commits landed alongside the override:

- `dc70527` — `fix(14): resolve D-38 bit-0 collision between TOXIN_PRESENT and OVERCROWDED` — moved `CELL_STATUS_TOXIN_PRESENT` and `ENTITY_STATUS_TOXIC` from `0x01` to `0x02` per D-38 / D-39 spec. Prior to the fix, `PerceptionBroadcaster`'s `cellStatus = (cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit` also stripped TOXIN_PRESENT every tick, so ambient-toxin cells never reached client vision. All 570 tests pass post-fix.
- `464ce9f` — `docs(14): document three-layer env state projection model` — added the three-layer table to `CLAUDE.md` Architecture section and D-38 / D-39 bit-layout blocks to the `Messages.CellView` Javadoc so the shadow-grid → bitmask-cache → wire-bitmask pipeline cannot be re-conflated.

### R13 "Cell flags system" — three-layer projection delivers R13 spirit

**Literal requirement:** REQUIREMENTS.md R13 reads "Environmental effects use Cell flags system (extending FLAG_OVERCROWDED pattern)."

**Shipped implementation — three layers** (D-38, D-39, D-40, D-41):

| Layer | Surface | Purpose |
|-------|---------|---------|
| 1 | `byte[][] toxinGrid`, `mutagenGrid` (0–255) on `EnvironmentEngine` | Authoritative intensity. CA diffusion, spline paths, gossip. Single bit cannot represent 0–255. |
| 2 | `Map<Position,Byte> cellStatusCache` + `Map<String,Byte> entityStatusCache`, rebuilt each tick in `EnvironmentEngine.buildStatusCaches()` per D-41 | Read-only bitmask projection. D-38: OVERCROWDED=bit 0, TOXIN_PRESENT=bit 1 (0x02), MUTAGEN_ZONE=bit 2 (0x04). D-39: TOXIC=bit 1 (0x02), MUTATING=bit 2, BUFFED=bit 3. **This IS the "Cell flags system" in spirit** — a keyed bitmask co-located with the shadow grids that produce it, rebuilt from authoritative state per tick. |
| 3 | `Messages.CellView.cellStatus` + `entityStatus` emitted per bot by `PerceptionBroadcaster.cellToView` | Zero-trust vision-scoped projection of layer 2. OVERCROWDED redacted to bot's 5x5 Moore count per D-40; other bits pass through unchanged post bit-0 fix. |

**Why not literal `Cell.flags` extension:**
1. Intensity 0–255 cannot fit a single bit — bit-based approach forces information loss at layer 1.
2. Cache locality + additive evolution — per-effect shadow grids enable per-effect virtual-thread parallelism (D-31 / D-33); a monolithic `Cell.flags` word serialises all effects.
3. Composite SENSOR stitched coverage (Plan 14-05 cycle-4 action item #8) requires a per-bot projection surface. `Cell.flags` has no per-bot semantics.
4. OVERCROWDED needs vision-scoped redaction per bot. Direct read of a global `Cell.FLAG_OVERCROWDED` cannot deliver this without a derived cache — which is exactly layer 2.
5. Extensibility — new env effects become new bits in `cellStatus` / `entityStatus` (6 bits each, 3 reserved) without expanding `Cell.flags`.

**Cell.flags retained for server-global state:** `FLAG_OVERCROWDED` (server-authoritative penalty calc) + `FLAG_STARVING` (entity-intrinsic, not spatially redacted) remain on `Cell.flags` unchanged. HeuristicBrain reads `FLAG_STARVING` directly via `cell.flags()` — STARVING is not projected into `entityStatus` because it does not need vision redaction.

**Result:** R13 spirit fully delivered via a richer three-layer model than the literal wording anticipated. Override accepted.

---

## Phase Gate Readiness

All 5 ROADMAP.md Success Criteria are satisfied (4 literally, 1 via documented intentional deviation). All three test tiers mandated by Plan 14-06 landed:

- **Roadmap-literal phase gate** — `EnvironmentPhaseGateIntegrationTest` (1/1 pass in full suite)
- **Supplemental env-engine determinism guard** — `EnvironmentDeterminismTest` (2/2 pass)
- **Supplemental WebSocket wire-path smoke** — `EnvironmentFullStackSmokeTest` (1/1 pass)

Full test suite: **570 tests, 0 failures, 3 ignored** (all documented). Spring startup clean. All six `14-XX-SUMMARY.md` files show `Self-Check: PASSED`.

Once the R13 override is accepted (or a literal implementation requested), Phase 14 is ready to be marked complete in ROADMAP.md and STATE.md.

---

*Verified: 2026-04-17T16:25:00Z*
*Verifier: Claude (gsd-verifier)*
