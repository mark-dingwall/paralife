---
phase: 13
reviewers: [gemini, codex]
reviewed_at: 2026-04-14T16:15:00+10:00
plans_reviewed: [13-01-PLAN.md, 13-02-PLAN.md, 13-03-PLAN.md, 13-04-PLAN.md]
---

# Cross-AI Plan Review — Phase 13

## Gemini Review

# Phase 13: Energy & Metabolism System - Plan Review

This phase introduces significant depth to the "living simulation" aspect of Paralife. The transition from a static energy model to a per-type metabolic system with environmental cycles (fertility/seasons) is well-aligned with the project's core value of emergent spatial behavior.

---

### 1. Summary
The implementation strategy is logically segmented into four waves, moving from foundational configuration and per-type logic to complex interaction mechanics (starvation/hybrid vigor) and finally environmental factors. The plan leverages Spring Boot's `@ConfigurationProperties` and Java 21's record patterns effectively. By maintaining backward compatibility in `SimulationConfig` while introducing granular `MetabolicProfile` settings, the plans minimize the risk of breaking existing tests during the transition. The inclusion of SPORE-specific reproductive bonuses and a progressive starvation model ensures that the "rock-paper-scissors" dynamics will be further differentiated by survival strategies, not just combat stats.

---

### 2. Strengths
*   **Granular Control:** The 30-knob configuration (10 per type) allows for fine-tuned balancing of the Catalyst (predator), Membrane (tank), and Spore (breeder) archetypes.
*   **Progressive Starvation:** Using an intensity formula for starvation modifiers rather than a binary state prevents "cliff-edge" death and creates more interesting survival windows.
*   **SPORE Archetype Differentiation:** The implementation of `range=2` reproduction and bonus offspring chance correctly fulfills the "r-strategist" requirement.
*   **Environmental Depth:** The introduction of soil fertility patches and seasonal sine waves provides the "spatial niches" mentioned in the project goals.
*   **Backward Compatibility:** Fallback defaults in `SimulationConfig` allow for a phased rollout without immediate massive refactoring of all existing entity-related code.

---

### 3. Concerns
*   **Memory Leak in Cooldown Map (HIGH):**
    *   `lastReproducedTick` is a `ConcurrentHashMap` in `ActionResolver`. There is no mention of a cleanup mechanism. As entities die and are removed from the `WorldGrid`, their IDs will persist in this map indefinitely, leading to a memory leak in long-running simulations.
*   **Computational Overhead of Per-Tick Randomness (MEDIUM):**
    *   Plan 02 calculates `hybridRate` and `bondDecayCost` using `random(min, max)` *per tick* for every `BondedPair`. If the population scales to thousands of entities, the aggregate cost of these random calls and float arithmetic may impact tick latency.
*   **SPORE "Teleportation" Reproduction (LOW):**
    *   The `range=2` logic suggests calling `direction.apply()` twice. If the intermediate cell is blocked but the destination is free, the Spore effectively "teleports" its offspring. This might be intended, but usually, range-based actions in grid sims require a clear path or a radius search.
*   **Season/Fertility Scale (LOW):**
    *   The 200-tick year length is quite short. With a 300-tick integration test, only 1.5 years pass. This may be insufficient to observe long-term population oscillation stability vs. seasonal extinction events.

---

### 4. Suggestions
*   **Implement Cooldown Cleanup:**
    *   Add a step in `SimulationEngine.processInteractions` to remove dead entity IDs from `ActionResolver.lastReproducedTick`, or use a Cache with TTL (e.g., Caffeine) instead of a raw `ConcurrentHashMap`.
*   **Cache BondedPair Stats:**
    *   Instead of calculating `hybridRate` every tick, calculate it once at the moment of bonding and store it as a transient field on the `BondedPair` record (or wrapper). This reduces per-tick CPU load significantly.
*   **Refine Starvation Lag Documentation:**
    *   Ensure the 1-tick lag between `FLAG_STARVING` being set and modifiers being applied is explicitly documented in `SimulationEngine.java` to prevent future "bugs" being reported by developers expecting immediate application.
*   **Validation for Config:**
    *   Add `@Min` or `@PositiveOrZero` annotations to the `TypeProfile` fields to ensure the simulation doesn't crash or behave erratically with negative energy decay or costs.
*   **Expand Integration Test:**
    *   Increase the integration test duration to 600-1000 ticks (3-5 years) to ensure that the seasonal cycles don't lead to a total population collapse after the first winter.

---

### 5. Risk Assessment
**Overall Risk: MEDIUM**

**Justification:**
The core logic is sound, but the introduction of a global cooldown map without a cleanup strategy is a definite technical debt item that will cause stability issues in production-like loads. Additionally, the complexity of the starvation intensity formula and the seasonal modulation requires very careful "knob-tuning" to avoid a simulation state where all entities starve within the first 100 ticks. However, the plan's focus on unit tests for each wave (`SimulationEngineTest`, `ActionResolverTest`) and a final integration test provides a strong safety net.

**Final Verdict:** **PROCEED** with the addition of a cleanup mechanism for the reproduction cooldown map.

---

## Codex Review

## Plan 01: Per-Type Metabolic Profiles

**Summary**
This is a strong foundation plan with the right split between `SimulationEngine` and `ActionResolver`, but it has a few design seams that will cause churn later: config namespace drift, spawn/max-energy ownership leaking into the WebSocket layer, and incomplete lifecycle handling for reproduction cooldown state.

**Strengths**
- Good decomposition: decay/combat in `SimulationEngine`, consumption/reproduction in `ActionResolver`.
- Backward-compatibility via `SimulationConfig` fallbacks reduces immediate test breakage.
- Explicit type archetypes are clear and map well to the project's emergent-behaviour goals.
- It correctly targets the widest blast-radius files up front.

**Concerns**
- `HIGH`: The config shape drifts from the locked decisions. The phase context says `paralife.simulation.<type>` and `paralife.simulation.starvation`, while this plan introduces `paralife.metabolism` and `paralife.starvation`. That will make later plans and docs inconsistent.
- `MEDIUM`: Injecting metabolic config into `WorldWebSocketHandler` leaks entity construction policy into the transport layer. Current `Particle.spawn()` is used broadly; max-energy logic should live in entity creation, not in the handler.
- `MEDIUM`: `lastReproducedTick` cleanup is not addressed. Entity IDs are remapped during bonding/composite transitions, so stale entries can accumulate and make future behaviour harder to reason about.
- `MEDIUM`: Keeping `CHILD_START_ENERGY` static is risky once `maxEnergy` becomes configurable by type. `Particle` constructors do not currently enforce `energy <= maxEnergy`.
- `LOW`: SPORE range-2 reproduction is still under-specified. "Walk two steps" is valid, but it should be explicit whether failure at range 2 falls back to range 1, and how bonus-child placement interacts with `claimedCells`.

**Suggestions**
- Pick one config namespace for the whole phase and keep every plan aligned to it.
- Move per-type spawn defaults into `Entity.Particle` or a dedicated spawner/factory.
- Add cross-field validation for `starvationFloor <= starvationThreshold <= 100`, `reproduceRange >= 1`, and `bonusOffspringChance in [0,1]`.
- Define cooldown pruning on death/remap and make bonus-offspring placement deterministic and conflict-safe.

**Risk Assessment**
`MEDIUM` — the plan is directionally correct, but the config/schema choices will create avoidable downstream churn if not corrected now.

---

## Plan 02: BondedPair Hybrid Vigor + Starvation

**Summary**
The plan has the right scope and sequencing, but it currently misses one required contract change and has a more serious gameplay-ordering problem: using the starvation flag as prior-tick state will produce stale combat modifiers after an entity recovers.

**Strengths**
- Good separation from Plan 01: bond metabolism and starvation are a clean second wave.
- Reuses existing config patterns instead of inventing new mutable state holders.
- Explicitly acknowledges that some hybrid-vigor domains have no current code path.

**Concerns**
- `HIGH`: The plan says `FLAG_STARVING` is visible in perception, but it does not modify `Messages.java` or `PerceptionBroadcaster.java`. `CellView` currently has no flag field, so this requirement is not actually met.
- `HIGH`: "Combat reads previous tick's flag" is unsafe. An entity can recover in `ActionResolver` after the flag was set, then still be treated as starving on the next tick because `processInteractions()` runs before flags are refreshed.
- `MEDIUM`: Averaging BondedPair starvation thresholds/floors equally is not quite right when types have different `maxEnergy`; weighted averaging by max energy is more coherent.
- `MEDIUM`: `ThreadLocalRandom.nextDouble(min, max)` breaks when `min == max`. The helper or validation must handle fixed-range configs explicitly.
- `MEDIUM`: Per-tick random hybrid vigor makes tests noisier and gives bonded-pair stats a jittery feel instead of a stable trait.

**Suggestions**
- Recompute starvation intensity from current energy wherever modifiers are applied; use `FLAG_STARVING` only for observability.
- Add a perception/message change if starvation visibility is a real requirement.
- Weight BondedPair starvation thresholds/floors by each type's max energy.
- Add tests for starvation recovery, stale-flag avoidance, and equal-bound config cases.

**Risk Assessment**
`HIGH` — there is a real correctness gap between the stated mechanic and the current event ordering.

---

## Plan 03: Soil Fertility + Seasonal Cycles

**Summary**
This is a good feature plan with strong use of existing primitives (`Cell.nutrientLevel`, `WorldGrid.setCell()`), but the season math is internally inconsistent and the config layout drifts again from the documented decisions.

**Strengths**
- Activates existing tech debt cleanly instead of adding a parallel resource system.
- Fertility patches plus seasonal modulation should create the kind of spatial/ecological pressure the project wants.
- Clamping spawn probability is pragmatic and avoids invalid probabilities.

**Concerns**
- `HIGH`: Season semantics are inconsistent. Raw sine gives tick 0 at baseline, not peak. The plan text/tests disagree on whether tick 0 is "spring rising" or "spring peak", so either the formula or the assertions are wrong.
- `MEDIUM`: Config prefix drift continues with separate `paralife.fertility` and `paralife.seasons`, while the phase context puts these under `paralife.simulation.*`.
- `MEDIUM`: `fertilityMultiplier` in `Messages.Tick` is misleading if it is really the global seasonal multiplier; local fertility already exists per cell.
- `MEDIUM`: `@PostConstruct` mutation is fine for prod, but it can surprise Spring tests and any future reset flow. Also, `WorldGrid.clear()` currently wipes fertility completely.
- `LOW`: Patch radius/world-size edge cases and toroidal overlap behaviour should be explicitly validated.

**Suggestions**
- Decide the phase alignment first, then encode it consistently. Use cosine if tick 0 must be peak, or keep sine and define tick 0 as spring midline/rising.
- Keep config nesting consistent with the rest of Phase 13.
- Rename the broadcast field to `seasonalMultiplier` or `nutrientSpawnMultiplier`.
- Add tests for toroidal patch wrapping and for reset/reinitialization behaviour.

**Risk Assessment**
`MEDIUM` — the feature shape is solid, but the season model needs to be made mathematically consistent before implementation.

---

## Plan 04: Integration Test

**Summary**
This is the weakest plan. It has the right intent, but as written it does not actually exercise the full metabolism system because it bypasses the `ActionResolver` paths where consumption and reproduction live.

**Strengths**
- Correct instinct to end Phase 13 with an end-to-end population-dynamics test.
- Reusing the existing population-dynamics test pattern is the right direction.

**Concerns**
- `HIGH`: The proposed harness only runs `SimulationEngine.processTick()`. That means no bot actions, no `resolveConsume()`, and no `resolveReproduce()`. A large part of Phase 13 would remain untested.
- `HIGH`: The plan is internally inconsistent about the test style. It says "follow `PopulationDynamicsTest`" but describes it as manual wiring; the actual test is `@SpringBootTest` with real bot/WebSocket flow.
- `MEDIUM`: The sample counting logic misses `CompositeMember` entities and BondedPair secondary types, so type survival can be misreported.
- `MEDIUM`: A stochastic 300-tick "all types survive at tick 300" assertion will be flaky without a reproducibility strategy or weaker invariants.
- `LOW`: "Two distinct spawn counts" is too weak as evidence of seasonal behaviour; randomness alone can satisfy it.

**Suggestions**
- Make this a real end-to-end test: either use `@SpringBootTest` plus `BotLauncher`, or manually run brains and `ActionResolver` each tick.
- Count type presence across `Particle`, `BondedPair` primary/secondary, and `CompositeMember`.
- Assert seasonal effects over windows or averaged periods, not single-tick randomness.
- Prefer robust assertions like "all types appear in late-run samples" over "all types alive exactly at tick 300" unless you can make the run deterministic.

**Risk Assessment**
`HIGH` — in its current form, this plan would give false confidence because it misses core action-driven metabolism behaviour.

---

## Consensus Summary

### Agreed Strengths
- **Well-structured wave decomposition** — Both reviewers praise the 4-wave dependency ordering as clean and logical (Gemini: "logically segmented into four waves"; Codex: "clean second wave")
- **Good use of existing patterns** — Both note effective reuse of @ConfigurationProperties, record patterns, and existing Cell flag infrastructure
- **Progressive starvation is well-designed** — Both agree the intensity formula over binary state is the right approach for creating interesting survival dynamics
- **SPORE archetype properly differentiated** — Both confirm the r-strategist bonuses (range=2, bonus offspring) correctly fulfill requirements
- **Backward compatibility approach is sound** — Both approve keeping SimulationConfig fallback defaults during transition

### Agreed Concerns
- **Cooldown map memory leak (HIGH)** — Both flag `lastReproducedTick` ConcurrentHashMap lacking cleanup on entity death. Gemini says "memory leak in long-running simulations"; Codex says "stale entries can accumulate". Plan 01 Task 2 does describe cleanup (`lastReproducedTick.keySet().removeIf(...)`) but both reviewers missed it or found it insufficient.
- **Starvation ordering / stale flag problem (HIGH)** — Codex explicitly flags that combat reads previous tick's flag as unsafe (entity can recover between flag-set and combat). Gemini implicitly acknowledges via "Refine Starvation Lag Documentation" suggestion. Core correctness concern.
- **Season math inconsistency (MEDIUM-HIGH)** — Both note the sine formula alignment issue. Gemini mentions it implicitly; Codex flags it as HIGH. Raw `sin(0) = 0` means tick 0 is baseline, not spring peak — plan text and test assertions disagree.
- **Integration test insufficient (MEDIUM-HIGH)** — Codex rates Plan 04 as HIGH risk because it only runs `processTick()` without bot actions, missing consume/reproduce paths. Gemini suggests extending duration to 600-1000 ticks. Both agree the test needs strengthening.
- **Per-tick random hybrid vigor is noisy (MEDIUM)** — Both suggest caching at formation time instead of computing per-tick. Gemini: "reduce per-tick CPU load"; Codex: "jittery feel instead of stable trait".

### Divergent Views
- **Config namespace** — Codex strongly flags config prefix drift (`paralife.metabolism` vs `paralife.simulation.<type>` from CONTEXT.md) as HIGH; Gemini doesn't mention it. Worth investigating — this is a discretion area in CONTEXT.md ("Config record organization — Claude's discretion").
- **FLAG_STARVING perception visibility** — Codex flags that `PerceptionBroadcaster`/`CellView` don't expose flags, so "visible in perception" (D-10) is not actually implemented. Gemini doesn't raise this. Valid concern if perception visibility is a requirement vs just cell-level flag.
- **SPORE teleportation** — Gemini raises that range-2 offspring could "teleport" past blocked intermediate cells. Codex mentions under-specification but doesn't call it a bug. Likely acceptable by design.
- **BondedPair starvation averaging** — Codex suggests weighted averaging by maxEnergy instead of equal averaging. Gemini doesn't raise this. Minor design refinement.
- **CHILD_START_ENERGY** — Codex flags static CHILD_START_ENERGY as risky with per-type maxEnergy. Gemini doesn't mention it. Valid concern for type balance.
