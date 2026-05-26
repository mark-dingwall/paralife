---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-26T17:45:27Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-PR2-REVIEW-DIFF.md", "src/main/java/com/paralife/diagnostics/DeathDiagnostics.java", "src/main/java/com/paralife/engine/SimulationEngine.java", "src/main/java/com/paralife/engine/EnvironmentEngine.java", "src/main/java/com/paralife/engine/DeathFinalizer.java", "src/main/java/com/paralife/engine/LiveEntityRegistry.java"]
usage:
  claude: { input: 37704, output: 427, cached: 564672, tool_calls: 3, elapsed_s: 212.1 }
  gemini: { input: 69226, output: 1405, cached: 0, tool_calls: 0, elapsed_s: 383.3 }
  codex: { input: 366512, output: 6986, cached: 286080, tool_calls: 14, elapsed_s: 138.4 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 5, elapsed_s: 180.1 }
synthesizer: claude
synthesized_at: 2026-05-26T17:45:27Z
---

# Cross-AI Review

## Claude Review

Confirmed leak breadth: `cleanupBot`, stalled-close, lost-race rollback all `unregister` without `recordDeath`. Enough to write.

---

# Review — PR #2 `feat/diagnostics-instrumentation`

Probes answered inline. Verdicts: **(a) PASS, (b) HIGH leak — wider than flagged, (c) two real misattributions, (d) PASS, (e) dead, remove.**

---

## HIGH

### H1 — `birthTick` leaks at every identity-transition + disconnect path (probe b)
`DeathDiagnostics.java:78` `recordBirth` (put) reaped **only** by `recordDeath` → `recordDeath` called **only** in `finalizeParticleDeath` (`DeathFinalizer.java:120`) and `finalizeBondedPairDeath` (`:146`). Every *other* `unregister` path drops the id with no `recordDeath`:

- Bond formation `SimulationEngine.java` (~ `unregister(predator.id)/unregister(prey.id)`) → 2 leaked particle entries per bond.
- Composite formation → 2 leaked BondedPair entries.
- `revertToBondedPair` / `dissolveToParticles` → leaked member ids.
- `checkPanicZone` total-death → leaked member ids.
- **Disconnect/stall**: `WorldWebSocketHandler.java:592, 831, 913` `liveEntityRegistry.unregister(entityId)` — no recordDeath.

PR flagged composite members only. Real surface = **all** of the above. Mechanism: `register`→`recordBirth` is the single birth chokepoint, but death reaping is NOT the single chokepoint — it sits in two of ~eight unregister sites. Unbounded `Map<String,Long>` growth while flag ON. The flag's stated purpose is **long scale runs with bot churn + emergent composites** — i.e. the exact workload that leaks fastest.

**Fix:** reap at the chokepoint, not the death-leaf. Add a `forget(entityId)` (remove from all 3 maps, no log/counter) and call it from `LiveEntityRegistry.unregister`. Then `recordDeath` = forget + log + count; transitions/disconnects = silent forget. One reaper mirrors the one birth chokepoint. Note: lifespan for transitioned ids still resets per id (see L2) — `forget` accepts that; lineage tracking is out of scope.

`lethalHint`/`preHitEnergy` do **not** share this leak — set+consumed same tick (hint at `@Order(10)`/`@Order(14)`, reaped in `processDeaths`/`processEnvDeaths` same tick). Only `birthTick` is long-lived.

---

## MEDIUM

### M1 — Composite-member deaths invisible to the diagnostic (probe b, attribution side)
`finalizeCompositeMemberDeath` (`DeathFinalizer.java`) → `handleMemberDeath` → `cleanupCompositeMemberCellViaFinalizer`. None call `recordDeath`. So `paralife.diag.deaths` counter + `DEATH-TRACE` log **never fire for CompositeMember**. Combat/env/panic-zone/dissolution member deaths = zero coverage. A death-cause census silently undercounts. Subset of H1 leak, plus a counter gap.
**Fix:** call `recordDeath(cm.id(), cm.type().name())` in `cleanupCompositeMemberCellViaFinalizer` (single member chokepoint). Add a `hintLethal` for the env CompositeMember branch at `EnvironmentEngine.java:~1305` (currently no hint there → members default STARVATION even when env-killed).

### M2 — Overcrowding misattributes decay/starvation kills as OVERCROWDING (probe c)
`SimulationEngine.java:1088`:
```java
if (deathDiagnostics != null && p.energy() - penalty <= 0)
    deathDiagnostics.hintLethal(p.id(), ...OVERCROWDING, p.energy());
```
Phase order: decay (P2) → overcrowding (P2.5) → death reap (P3), all in `@Order(10)`. Decay does **not** hint. A particle decayed to `energy==0` this tick is still on-grid at P2.5. Guard `0 - penalty <= 0` = true → `hintLethal` claims it (putIfAbsent, lethalHint empty) → logged **OVERCROWDING**. Real cause = starvation.

**Repro:** dense food-starved cluster (≥`overcrowdingThreshold` neighbours), particle hits 0 via decay, gets stamped OVERCROWDING in P2.5. This biases the **headline 78%-starvation metric the tool exists to produce.**
**Fix:** require entity was alive before the blow — `if (... && p.energy() > 0 && p.energy() - penalty <= 0)`. Same hardening on BondedPair branch `:1093`. Combat sites (`:883, :888`) run P1 (pre-decay) so lower risk, but add `> 0` for symmetry.

### M3 — `envCauseAt` LIGHTNING default misattributes mutagen-DoT (probe c)
`EnvironmentEngine.java:envCauseAt` checks `toxinGrid`/`mutagenGrid` at death `(x,y)`, else **LIGHTNING**. But infection DoT (`tickBuffsAndInfections`) damages by the per-entity `Infection` record, **not** by standing on a strain cell.
**Repro:** entity infected on strain cell → moves to clean non-toxin cell (or strain cell decays via `zoneDecayTicks`) → DoT kills it there → `mutagenGrid[x][y]==0`, `toxinGrid==0` → logged **LIGHTNING**, true cause MUTAGEN.
Also: toxin **splash** (`SplashDelta`→`applyDeltaToOccupant`) hints COMBAT, true cause toxin.
**Fix:** `envCauseAt` should take the id and check `envCleanupHooksBean.getInfections().containsKey(id)` → MUTAGEN before defaulting. Replace blind LIGHTNING default with `UNKNOWN` (enum already has it) unless a lightning signal exists — LIGHTNING leaving no grid trace means it's indistinguishable from "env death, cause unknown," so don't assert it.

---

## LOW / TRIVIAL

### L1 — `histogram()` + `causeCounts` dead and duplicate (probe e)
`DeathDiagnostics.java:histogram()` public, zero callers. `causeCounts` (LongAdder map) is a **second tally** of data already in the `paralife.diag.deaths` Micrometer counter (tagged `cause`). Two sources of truth, one unused.
**Verdict:** remove both `histogram()` and `causeCounts` — query the counter via `meterRegistry`. Keep only if a shutdown-summary log is actually planned; then wire it and drop the dead `public`. Cheap either way; don't leave it dangling.

### L2 — lifespan = id-lifetime, not lineage (trivial)
`lifespan = now - birth` resets at each identity transition (bond→composite→revert→dissolve each `register` fresh). Leaked predecessors (H1) never log a lifespan. So `lifespanTicks` under-reports any entity that ever bonded/composited. Document in Javadoc: "lifespan = grid-id lifetime, not biological lineage."

### L3 — `preHitEnergy=0` for env + (post-fix) overcrowding deaths (trivial)
Env hints pass literal `0` → `DEATH-TRACE preHitEnergy=0` reads like a real measurement. Either pass the true pre-hit energy at the env sweep (re-read occupant energy before finalize) or log `preHitEnergy=n/a` when unhinted.

---

## PASS

**(a) thread-safety** — `recordBirth` on WS + tick threads; CHM `put`. `hintLethal`/`recordDeath` tick-only. Birth is happens-before death for any id (entity must be on grid before it can die; `register` is `synchronized`, `recordBirth` inside it). Keys disjoint across threads. `putIfAbsent` "first-cause-wins" is decorative under single-threaded hint path but harmless. No lost/torn writes. No cross-map atomicity, none needed.

**(d) flag-off inertness** — `@ConditionalOnProperty(havingValue="true")`, no `matchIfMissing` → bean absent when off. All 4 consumers use `@Autowired(required=false)` setters; ctor injection (`TickEngine`,`MeterRegistry`) lives only inside the conditional bean. No `@Lazy` remnant in any setter (checked all 4: `SimulationEngine.java:300`, `EnvironmentEngine`, `DeathFinalizer.java:84`, `LiveEntityRegistry.java:91`). Zero boot coupling when off. Confirmed clean.

---

## Priority
Fix **M2** (corrupts the metric the tool reports) and **H1** (unbounded leak at scale) before any long run. M1/M3 = correctness of the census. L1–L3 = polish.

One structural note: birth has one chokepoint (`register`), death has two leaves. Collapse death to one reaper (`LiveEntityRegistry.unregister` → `forget`) and H1+M1 both close. Symmetric chokepoints, leak gone.

---

## Gemini Review

Here is the comprehensive cross-AI review based on the provided instructions and PR scope.

### (a) Thread-Safety
**Severity:** Trivial / Pass
**Mechanism:** `recordBirth` is invoked on the WS inbound thread during registration, while `hintLethal` and `recordDeath` run on the tick thread. Since the `DeathDiagnostics` state maps use `ConcurrentHashMap` and values are inserted per unique `entityId`, this is inherently thread-safe. A newly registered entity won't be processed by the simulation sweeps until at least the next tick, ensuring a strict happens-before relationship between `recordBirth` and `recordDeath`. `putIfAbsent` operates sequentially within the tick thread without races.
**Recommendation:** None required. The concurrent structures and ordering are safe.

### (b) Map-Entry Lifecycle (Most Important)
**Severity:** BLOCKING
**File:** `SimulationEngine.java`, `DeathFinalizer.java`, `LiveEntityRegistry.java`
**Mechanism:** The tracking maps suffer from two massive unbounded memory leaks because `recordDeath` is bypassed in several lifecycles:
1. **Real CompositeMember Deaths:** `CompositeMember` entities are registered (gaining a `birthTick`), but when they die (via combat, env, or panic zone), `DeathFinalizer.finalizeCompositeMemberDeath` and `SimulationEngine.handleMemberDeath` never call `recordDeath`. The entity vanishes, and its maps leak forever.
2. **Entity Transformations:** When entities are absorbed or transformed (e.g., predator/prey into a `BondedPair`, BondedPairs into `CompositeMembers`, or composites dissolving/reverting into `Particles`), their original IDs are unregistered from `LiveEntityRegistry` but do NOT go through `DeathFinalizer`. Their `birthTick` entries leak forever.
**Recommendation:** 
1. In `SimulationEngine.java`, add `if (deathDiagnostics != null) deathDiagnostics.recordDeath(cm.id(), "COMPOSITE_MEMBER");` inside `cleanupCompositeMemberCellViaFinalizer()` (this cleanly covers both standard member deaths and panic-zone total deaths). Also, ensure `CompositeMember` branches in `applyDeltaToOccupant` and `EnvironmentEngine.processEnvDeaths` call `hintLethal`.
2. Add a `public void discardLifecycle(String entityId)` to `DeathDiagnostics` that silently removes the map entries (`birthTick.remove`, etc.) without bumping the `causeCounts`. Call this whenever an entity is transformed/absorbed (e.g., during bond formation, composite formation, dissolve, and revert).

### (c) Cause Attribution Correctness / Ordering
**Severity:** HIGH
**File:** `EnvironmentEngine.java` (Line 1272)
**Mechanism (Env Heuristics):** `envCauseAt` relies on the cell's shadow grid state at the end of the tick, independent of which pipeline dealt the LETHAL blow.
**Reproducing Scenario:** An entity with 5 energy stands on a Toxin cell (intensity > 0). It survives the Toxin damage, but gets struck by Lightning for 10 LETHAL damage. `EnvironmentEngine.processEnvDeaths` sweeps the dead entity, evaluates `envCauseAt(x, y)`, sees `toxinGrid > 0`, and falsely attributes the death to `TOXIN` instead of `LIGHTNING`.
**Recommendation:** Delete `envCauseAt` and the hints in `processEnvDeaths`. Instead, call `hintLethal(id, Cause.X, energy)` *at the exact moment* lethal damage is applied inside the existing loops of `resolveToxinCollisions` (TOXIN), `tickBuffsAndInfections` (MUTAGEN), and `damageEntityAt` (LIGHTNING).

**Severity:** HIGH
**File:** `SimulationEngine.java` (Line 884)
**Mechanism (Splash Misattribution):** `SplashDelta` (toxin splash damage) is applied via `applyDeltaToOccupant`, which hardcodes the lethal hint to `Cause.COMBAT`.
**Reproducing Scenario:** Entity A attacks Entity B (on a toxic cell). Entity A receives a LETHAL `SplashDelta` of -10. `applyDeltaToOccupant` logs the LETHAL hint as `COMBAT`, misattributing an environmental toxin death as a combat death.
**Recommendation:** Pass the `Cause` into `applyDeltaToOccupant` (e.g., allow `InteractionResult` to supply it) or have the caller set `hintLethal` before passing the delta down.

**Severity:** HIGH
**File:** `SimulationEngine.java` (Line 1089)
**Mechanism (Overcrowding Shadowing Starvation):** If an entity dies from Energy Decay (Phase 2), it correctly receives no hint (defaulting to STARVATION later). However, Phase 2.5 (Overcrowding) processes the entity next. Because its energy is already 0, the check `p.energy() - penalty <= 0` evaluates to true, and `hintLethal` is called. Since `putIfAbsent` finds no existing hint, it overwrites the cause. 
**Reproducing Scenario:** A Particle at 2 energy takes 2 Decay damage (drops to 0). Overcrowding runs, sees 0 energy, and incorrectly claims the death as `OVERCROWDING`.
**Recommendation:** Guard the Overcrowding hint emission with an explicit `> 0` check: `if (deathDiagnostics != null && p.energy() > 0 && p.energy() - penalty <= 0)`.

### (d) Flag-Off Inertness
**Severity:** Trivial / Pass
**Mechanism:** The `@ConditionalOnProperty` on `DeathDiagnostics` correctly prevents instantiation when the flag is false/absent. Every injection site leverages `@Autowired(required = false)`, ensuring Spring safely ignores the setters. All execution paths use `if (deathDiagnostics != null)` guards. No boot-time coupling or runtime NPEs exist. 
**Recommendation:** None required.

### (e) `histogram()` Deadness
**Severity:** LOW
**File:** `DeathDiagnostics.java` (Line 106)
**Mechanism:** The public `histogram()` method has no caller in the codebase.
**Recommendation:** If this is an intended observability hook for external use (e.g., JMX, Actuator endpoints, or scheduled summaries), retaining it is cheap and valid. If strict YAGNI applies, remove it until an explicit consumer exists.

---

## Codex Review

**Findings**

`blocking` `src/main/java/com/paralife/engine/LiveEntityRegistry.java:130`, `src/main/java/com/paralife/engine/SimulationEngine.java:821`, `src/main/java/com/paralife/engine/DeathFinalizer.java:196`  
`recordBirth()` is attached to every `LiveEntityRegistry.register()`, but only `Particle` and `BondedPair` deaths call `recordDeath()`. Composite members are definitely registered at composite formation, then sim deaths go through `SimulationEngine.handleMemberDeath()` and env deaths go through `DeathFinalizer.finalizeCompositeMemberDeath()` -> `handleMemberDeath()`, neither of which records the death. The cleanup path only unregisters the member, so `birthTick` leaks for every dead composite member while the flag is on. Broader lifecycle issue: non-death unregisters also leak diagnostic identities today, including bond formation source particles, composite formation source bonded pairs, dissolve/revert source members, disconnect cleanup, and register-first rollback failures.  
Recommendation: add a non-counting `DeathDiagnostics.forget(entityId)` and call it from `LiveEntityRegistry.unregister()` after successful removal, while still calling `recordDeath()` before unregister on actual deaths. For composite member deaths, record centrally in `cleanupCompositeMemberCellViaFinalizer()` or `handleMemberDeath()` so sim, env, and panic-zone deaths are covered. Also add composite-member lethal hints; currently both combat and env member deaths would otherwise default to starvation.

`high` `src/main/java/com/paralife/engine/SimulationEngine.java:1090`  
Overcrowding can shadow starvation in the same tick. `processEnergyDecay()` can reduce an entity to zero with no hint, then `processOvercrowding()` sees `p.energy() == 0`, satisfies `p.energy() - penalty <= 0`, and claims `OVERCROWDING` before `processDeaths()`. Repro: particle energy equals decay cost, is overcrowded, and dies during decay; the trace reports overcrowding, not starvation.  
Recommendation: only hint overcrowding when crossing from alive to dead: `p.energy() > 0 && p.energy() - penalty <= 0`, same for bonded pairs.

`high` `src/main/java/com/paralife/engine/SimulationEngine.java:884`  
`applyDeltaToOccupant()` handles both `CombatDelta` and toxin `SplashDelta`, but always hints lethal negative deltas as `COMBAT`. A toxin splash kill is therefore counted as combat, and because sim death finalization runs in the same `@Order(10)` tick before the env sweep, the env engine cannot correct it. Repro: low-energy attacker hits a defender standing on toxin; splash kills attacker; `DEATH-TRACE` cause is `COMBAT`.  
Recommendation: carry cause through the delta type, e.g. `CombatDelta(..., Cause.COMBAT)` and `SplashDelta(..., Cause.TOXIN)`, or add a separate apply path for splash that hints `TOXIN`.

`medium` `src/main/java/com/paralife/engine/EnvironmentEngine.java:1273`  
`envCauseAt()` defaults to `LIGHTNING` whenever the death cell has no toxin or mutagen grid value. That misattributes mutagen infection DoT deaths after the entity moves off the mutagen grid or after the zone decays. Repro: entity is infected on a mutagen cell, moves to a clean cell, infection DoT reaches zero; `processEnvDeaths()` reports `LIGHTNING` even though no lightning occurred.  
Recommendation: tag lethal env damage at the damage site with the known source (`TOXIN`, `MUTAGEN`, `LIGHTNING`) instead of inferring during the sweep. Keep the sweep heuristic only as fallback/unknown.

`trivial` `src/main/java/com/paralife/diagnostics/DeathDiagnostics.java:105`  
`histogram()` has no caller. It is cheap, but it is public API surface without an active use.  
Recommendation: either remove it/package-private it now, or add the planned periodic/final summary caller in this PR so the API is justified.

**Probe Answers**

Thread-safety: the `ConcurrentHashMap`s and `putIfAbsent()` are mechanically safe for the stated threads. First-claim-wins is fine for tick-thread hints. The real problem is lifecycle, not map concurrency.

Flag-off inertness: looks clean. The bean is conditional, all call sites are setter-injected with `@Autowired(required=false)` and null-guarded, and I did not find a lingering `@Lazy required=false` `DeathDiagnostics` injection pattern.

Ordering: sim `@Order(10)` before env `@Order(14)` means sim hints can permanently shadow env attribution because `putIfAbsent()` wins. That is correct only if the sim hint is truly combat/overcrowding; toxin splash currently violates that.

---

## Opencode Review

I'll review each file against the five probes.Here's the review.

---

## Review: PR #2 `feat/diagnostics-instrumentation` — DeathDiagnostics

### Finding 1 — **BLOCKING**: CompositeMember `birthTick` map-entry leak

**File:** `SimulationEngine.java:1189–1199`, `SimulationEngine.java:894–896`, `EnvironmentEngine.java:1307–1308`

**Mechanism:** CompositeMembers ARE registered in `LiveEntityRegistry` at formation (`SimulationEngine.java` at composite-formation site in `processInteractions` calls `liveEntityRegistry.register(memberId1, ...)`), which calls `recordBirth(memberId)`. Every composite formation adds 2 entries to `birthTick`.

Three death/transition paths fail to call `recordDeath`:
1. `handleMemberDeath` → `cleanupCompositeMemberCellViaFinalizer` — no `recordDeath` call.
2. `revertToBondedPair` — unregisters composite members, registers BondedPair, no `recordDeath` for the old CompositeMember ids.
3. `dissolveToParticles` — unregisters composite members, registers Particles, no `recordDeath` for the old CompositeMember ids.

Additionally, `hintLethal` is never set for CompositeMembers anywhere (not in `applyDeltaToOccupant:893–896`, not in `processOvercrowding` which skips CompositeMembers at `:1076`, and not in `processEnvDeaths:1307`). So `lethalHint` and `preHitEnergy` don't leak for CompositeMembers — but `birthTick` entries accumulate unboundedly with every composite formed/dissolved/reverted/killed.

**Reproducing scenario:** Run with `paralife.diagnostics.death-trace.enabled=true` on a grid with `composite.can-form-composites=true`. Every composite that forms, reverts, dissolves, or dies adds persistent garbage to `birthTick`. After 500 ticks with typical formation rates, `birthTick.size()` will significantly exceed `LiveEntityRegistry.size()`.

**Recommendation:** Add `recordDeath(cm.id(), "COMPOSITE")` at the top of `cleanupCompositeMemberCellViaFinalizer` (before the cleanup wipes state). Composite member transitions (revert/dissolve) would then record a "death" — which is semantically accurate since the CompositeMember identity terminates. Use a distinct type string like `"COMPOSITE"`.

---

### Finding 2 — **HIGH**: `envCauseAt(…).LIGHTNING` misattribution on toxin/mutagen cells

**File:** `EnvironmentEngine.java:1276–1280`

**Mechanism:** `envCauseAt` returns the first match: `TOXIN` if toxinGrid > 0, else `MUTAGEN` if mutagenGrid > 0, else `LIGHTNING`. Lightning damage fires during the same tick (@Order(14)) as toxin/mutagen damage. If an entity sits on a cell with mild toxin residue (intensity 1/255) AND is killed by lightning, `envCauseAt` returns `TOXIN`.

**Reproducing scenario:**
1. Toxin event runs, deposits intensity ≥ 1 on cell (x=10,y=10).
2. Same tick, lightning Poisson roll fires, strikes (cx=10,cy=10).
3. Entity at (10,10) has low energy (say 5). Lightning does damage=10 → energy=0.
4. `processEnvDeaths` calls `hintLethal(id, envCauseAt(10,10), 0)` → `toxinGrid[10][10] > 0` → cause=TOXIN. The real kill was lightning.

The TOXIN > MUTAGEN > LIGHTNING priority is a reasonable best-effort hierarchy (persistent-grid effects checked first), but cells with both toxin + lightning or mutagen + lightning produce wrong attribution. At production defaults with active toxin events, this will happen regularly in the toxin plume's overlap with lightning strike coordinates.

**Recommendation:** Either (a) add a comment acknowledging the best-effort heuristic and its limitations, or (b) thread a `lightningKillSet` through the lightning strike (populate in `spawnLightning`'s inner-radius damage loop, consult in `envCauseAt` after toxin/mutagen checks). Option (a) is acceptable if precision isn't needed — this is diagnostics, not billing.

---

### Finding 3 — **MEDIUM**: `recordDeath` re-creates Micrometer counter on every death

**File:** `DeathDiagnostics.java:90–94`

**Mechanism:**
```java
Counter.builder("paralife.diag.deaths")
    .tag("cause", ...).tag("type", ...)
    .register(meterRegistry)
    .increment();
```

This does a registry lookup per death to find-or-create the counter. With Micrometer's composite registry this is O(1) but not free — it walks the registry's internal map. At 1000+ entities dying per tick, this adds measurable overhead. The domain is 4 types × 7 causes = 28 possible counters; pre-creating them avoids the per-death lookup.

**Recommendation:** Pre-create counters in the constructor or lazily via `computeIfAbsent` on a local map, then only `.increment()` at death time.

---

### Finding 4 — **MEDIUM**: `UNKNOWN` cause is unreachable

**File:** `DeathDiagnostics.java:46`

`Cause.UNKNOWN` is defined but never assigned anywhere. `hintLethal`'s `putIfAbsent` + `recordDeath`'s `getOrDefault(..., STARVATION)` means the only way `UNKNOWN` would appear is through `histogram()` if `causeCounts` had no STARVATION key — but it's unreachable.

**Recommendation:** Remove `UNKNOWN`, or add a `recordDeath` fallback path that writes it when all maps are empty (defensive).

---

### Finding 5 — **LOW**: `envCauseAt` called for CompositeMember but hint not used

**File:** `EnvironmentEngine.java:1307–1308`

The CompositeMember branch in `processEnvDeaths` does NOT call `hintLethal` (unlike the Particle and BondedPair branches), but `envCauseAt` is conceptually applicable. This isn't a bug — CompositeMember hints aren't set anywhere — but it's the only entity type with zero diagnostic coverage in the env-death sweep.

**Recommendation:** Not blocking; low priority. If Finding 1 is addressed and CompositeMembers get `recordDeath`, add `hintLethal(cm.id(), envCauseAt(x,y), 0)` here for symmetry.

---

### Finding 6 — **LOW**: `histogram()` is dead public API

**File:** `DeathDiagnostics.java:103–107`

No callers. Data exits via log + counter. Keeping it costs nothing (cheap future hook), but it's dead code. The ConcurrentHashMap return type is also misleading — no concurrency is needed in the returned map.

**Recommendation:** Either remove or add a brief Javadoc tagging it as "future summarisation hook — not currently wired." Replace the return type with `Map<Cause, Long>` (immutable copy).

---

### Probe summary

| Probe | Verdict |
|-------|---------|
| (a) Thread-safety | **Correct.** Different entity IDs, tick-thread-only death paths, CHM + putIfAbsent sufficient. No race. |
| (b) Map-entry lifecycle | **BLOCKING LEAK.** CompositeMember `birthTick` entries never reaped (Finding 1). `lethalHint` and `preHitEnergy` safe — never populated for CompositeMembers. |
| (c) Cause attribution | **Contains best-effort misattribution.** `envCauseAt` LIGHTNING default is conceptually right but TOXIN/MUTAGEN take priority over co-located lightning (Finding 2). Combat-vs-env ordering is correct — `@Order(10)` combat hints fire first, `@Order(14)` env wins if combat didn't kill. No cross-priority shadowing. |
| (d) Flag-off inertness | **Correct.** No `@Lazy` remnant on `DeathDiagnostics` references. All setters `@Autowired(required=false)`. All call sites guarded by `if (deathDiagnostics != null)`. Bean absent when flag is off. |
| (e) `histogram()` deadness | **Dead but harmless.** No callers. Keep as cheap future hook or remove. |

---

## Consensus Summary

### Agreed Strengths
- **Thread-safety is sound (4/4 reviewers, PASS).** `recordBirth` runs on the WS inbound thread inside `synchronized` `register`; `hintLethal`/`recordDeath` run tick-thread-only. `ConcurrentHashMap` + `putIfAbsent`, disjoint keys per entityId, and a strict happens-before (entity must be on-grid before it can die) mean no lost/torn writes. First-claim-wins is decorative under the single-threaded hint path but harmless.
- **Flag-off inertness is clean (4/4 reviewers, PASS).** `@ConditionalOnProperty(havingValue="true")` with no `matchIfMissing` keeps the bean absent when off. All consumers use `@Autowired(required=false)` setters guarded by `if (deathDiagnostics != null)`. No `@Lazy` remnant on any of the 4 injection sites; zero boot coupling.

### Agreed Concerns
- **[BLOCKING — 4/4] `birthTick` map-entry leak.** `recordBirth` fires at the single `register` chokepoint, but `recordDeath` sits only in `finalizeParticleDeath`/`finalizeBondedPairDeath`. CompositeMember deaths (`cleanupCompositeMemberCellViaFinalizer` / `handleMemberDeath`) never reap. Three reviewers (claude/gemini/codex) extend the surface to *every* non-death `unregister`: bond formation, composite formation, dissolve/revert, disconnect/stall (`WorldWebSocketHandler` ~592/831/913), and register-rollback. Unbounded `Map<String,Long>` growth under exactly the long-run-with-churn workload the flag targets. Consensus fix: a non-counting reaper (`forget`/`discardLifecycle`) called from `LiveEntityRegistry.unregister`, with `recordDeath` retained on true death paths — collapses death to one chokepoint mirroring birth. `lethalHint`/`preHitEnergy` do *not* leak (set+consumed same tick).
- **[HIGH — 3/4: claude, gemini, codex] Overcrowding shadows starvation.** Decay (P2) drops a particle to `energy==0` with no hint; overcrowding (P2.5) then sees `0 - penalty <= 0` true and stamps OVERCROWDING via `putIfAbsent` before death reap (P3). Biases the headline ~78%-starvation metric the tool exists to produce. Fix: guard with `p.energy() > 0 && p.energy() - penalty <= 0`, same on the BondedPair branch. (opencode did not raise this.)
- **[HIGH — 3/4: claude, gemini, codex] Toxin splash misattributed as COMBAT.** `applyDeltaToOccupant` hardcodes the lethal hint to COMBAT for both `CombatDelta` and toxin `SplashDelta`; sim finalization at `@Order(10)` runs before the env sweep so it cannot be corrected. Fix: carry `Cause` through the delta type (`SplashDelta → TOXIN`). (opencode did not raise this.)
- **[HIGH/MEDIUM — 4/4] `envCauseAt` infers cause from end-of-tick grid state and misattributes.** Two failure shapes surfaced: (1) mutagen/infection DoT kills an entity that has moved off the strain cell (or the zone decayed) → grid reads clean → falsely LIGHTNING (claude, codex); (2) lightning kills an entity standing on a toxin/mutagen cell → grid reads toxin → falsely TOXIN/MUTAGEN (gemini, opencode). Same root: cause is inferred at the sweep, not tagged at the damage site. Consensus fix: `hintLethal` at the exact lethal-damage application in `resolveToxinCollisions`/`tickBuffsAndInfections`/`damageEntityAt`; keep the grid heuristic only as a labeled fallback.
- **[MEDIUM/LOW — 4/4] CompositeMembers have zero diagnostic coverage.** No `hintLethal` is set for members anywhere, so even once they're reaped they default to STARVATION regardless of true cause. Add member hints (env branch ~`EnvironmentEngine:1305`, splash branch) alongside the recordDeath fix.
- **[LOW — 4/4] `histogram()` is dead public API.** No callers. claude/opencode additionally note `causeCounts` (LongAdder map) is a second tally duplicating the tagged `paralife.diag.deaths` Micrometer counter — two sources of truth. Consensus: remove both, or wire the planned shutdown/summary consumer in this PR to justify the surface.

### Divergent Views
- **Leak-fix semantics — `forget()` vs. `recordDeath()` on transitions.** claude/gemini/codex favor a *silent* reaper at the `unregister` chokepoint (a transition is not a death). opencode instead proposes calling `recordDeath(cm.id(), "COMPOSITE")` in cleanup, arguing the CompositeMember identity genuinely terminates. Worth deciding deliberately: silent-forget keeps the death census honest but logs no lifespan for transitioned ids; counting-as-death inflates death counts but preserves lineage signal.
- **`Cause.UNKNOWN` — remove vs. promote.** opencode flags UNKNOWN as unreachable dead enum and suggests removing it. claude argues the opposite: replace the blind LIGHTNING default with UNKNOWN, since lightning leaves no grid trace and is indistinguishable from "env death, cause unknown." Directly contradictory — resolve as part of the `envCauseAt` rework.
- **Micrometer counter overhead (opencode only, MEDIUM).** opencode flags per-death `Counter.builder(...).register()` registry lookups at 1000+ deaths/tick; recommends pre-creating the 28 (4 types × 7 causes) counters or `computeIfAbsent` caching. Not raised by others — verify whether tick-rate death volume makes this measurable before acting.
- **`preHitEnergy=0` / lifespan-as-id-lifetime (claude only, TRIVIAL).** claude notes env hints pass literal `0` (reads like a real measurement; log `n/a` when unhinted) and that `lifespan = now - birth` resets per identity transition (document as grid-id lifetime, not biological lineage). Not corroborated; low stakes.
