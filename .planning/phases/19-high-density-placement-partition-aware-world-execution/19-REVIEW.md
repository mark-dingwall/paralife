---
phase: 19-high-density-placement-partition-aware-world-execution
reviewed: 2026-05-01T00:00:00Z
depth: standard
files_reviewed: 11
files_reviewed_list:
  - src/main/java/com/paralife/admission/AdmissionMetrics.java
  - src/main/java/com/paralife/admission/OutboundSender.java
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/engine/DeathFinalizer.java
  - src/main/java/com/paralife/engine/EligibleCellIndex.java
  - src/main/java/com/paralife/engine/EnvironmentEngine.java
  - src/main/java/com/paralife/engine/LiveEntityRegistry.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/engine/TickEngine.java
  - src/main/java/com/paralife/websocket/TickBroadcaster.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
findings:
  critical: 0
  warning: 3
  info: 3
  total: 6
status: issues_found
---

# Phase 19: Code Review Report

**Reviewed:** 2026-05-01
**Depth:** standard
**Files Reviewed:** 11
**Status:** issues_found

## Summary

Phase 19 ships the `EligibleCellIndex` O(1) sparse-set placement, `LiveEntityRegistry` O(N) entity iteration, the `FrameEmitListener` test seam, and the `GoldenTraceEquivalenceTest` gate. The prior cross-AI review (REVIEWS.md) called out six HIGH-tier blockers; the executed code addresses four of them cleanly:

- **CONSENSUS-H4** (`cellStatusCache` thread-safety): resolved — `volatile` + swap-and-allocate staging map; `cellStatusCacheView()` returns the volatile field directly (line 1470), which is always a `Collections.unmodifiableMap` wrapper of the previously-mutated staging map. Safe.
- **CONSENSUS-H5** (wrong package paths): resolved — all imports use `com.paralife.admission.OutboundSender` and `com.paralife.engine.TickEvent`.
- **CONSENSUS-H6** (`attemptPlacementForTest` visibility): resolved — method is `public` (line 247, WorldWebSocketHandler).
- **LOW-10** (`Throwable` vs `Exception` catch): resolved — `catch (Exception listenerEx)` in `OutboundSender.drainLoop` line 222.
- **CONSENSUS-H1 / CONSENSUS-H2 / CONSENSUS-H3**: all three encoded in the shipped code and tests as documented by OPTION-B + H2/H3 fixes.

Three warnings remain: two are concurrency hazards that survived unnoticed, one is a logic gap in the entitySnapshot fallback. Three info items cover dead/stale code.

---

## Warnings

### WR-01: `entityStatusCache` is a plain `HashMap` exposed to `PerceptionBroadcaster` with no thread-safety

**File:** `src/main/java/com/paralife/engine/EnvironmentEngine.java:187,1473-1474`

**Issue:** `cellStatusCache` received the volatile + swap-and-allocate fix (CONSENSUS-H4). `entityStatusCache` did NOT. It is a plain `HashMap` (line 187), cleared and mutated on the tick thread, and returned by `entityStatusCacheView()` (line 1473) as a `Collections.unmodifiableMap(entityStatusCache)` — a live view of the underlying mutable map. `PerceptionBroadcaster` calls this accessor during tick dispatch (same thread), so in the current pipeline there is no cross-thread race today. However, any future caller on a different thread (e.g., a health endpoint or a Phase 19.1 parallel perception path) would get concurrent HashMap mutation silently. The asymmetry with `cellStatusCache` is a latent hazard and violates the principle of least surprise given the Phase 19.1 parallelism plan.

**Fix:** Apply the same swap-and-allocate pattern to `entityStatusCache`:
```java
// EnvironmentEngine field:
private volatile Map<String, Byte> entityStatusCache = Map.of();
private Map<String, Byte> entityStatusStaging = new HashMap<>();

// In onTick, replace: entityStatusCache.clear()
// with: entityStatusStaging.clear()

// In buildStatusCaches, mutate entityStatusStaging throughout, then at the end:
this.entityStatusCache = Collections.unmodifiableMap(entityStatusStaging);
this.entityStatusStaging = new HashMap<>();

// entityStatusCacheView():
Map<String, Byte> entityStatusCacheView() { return entityStatusCache; }
```
This is a small mechanical change that mirrors the already-shipped CONSENSUS-H4 fix exactly.

---

### WR-02: `EligibleCellIndex.rebuildForTest()` calls `initialize()` which reads `environmentEngine.cellStatusCacheView()` without holding the index monitor

**File:** `src/main/java/com/paralife/engine/EligibleCellIndex.java:251-255`

**Issue:** `rebuildForTest()` is `synchronized` (acquires the index monitor). It calls `initialize()` which is NOT `synchronized`. `initialize()` calls `environmentEngine.cellStatusCacheView()` then loops over the grid calling `addInternal()`. Because `rebuildForTest` holds the monitor, the `addInternal` calls are safe. However, `initialize()` is also called from `@PostConstruct` WITHOUT any lock. If Spring ever triggers a concurrent bean accessor (unlikely but possible during parallel context startup), the dense array and posInDense array could be written by two threads simultaneously. More critically: `initialize()` is package-private, callable from tests, and its body has no synchronization. A test that calls `initialize()` directly (not via `rebuildForTest`) would corrupt the sparse-set state without any warning.

**Fix:** Add `synchronized` to `initialize()`, or add a clear Javadoc warning that it must only be called while holding the index monitor:
```java
@PostConstruct
public synchronized void initialize() { ... }
```
The `@PostConstruct` synchronized call is safe because Spring does not call `@PostConstruct` concurrently for a single bean instance.

---

### WR-03: `entitySnapshot` fallback in `SimulationEngine` silently produces `entityId="_"` entries

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:271-281`

**Issue:** The back-compat grid-scan fallback in `entitySnapshot()` creates `EntityEntry` objects with `entityId="_"` (literal underscore). This string is then used in lifecycle-hook calls within `processDeaths` — specifically, an entity with `id="_"` would be passed to `liveEntityRegistry.unregister("_")` if it died. In a scenario where both the fallback path is active (registry null or empty) AND `liveEntityRegistry` is non-null, the underscore-keyed `unregister` call is idempotent (no entry for `"_"` exists), so no crash. But downstream code that logs or tracks entity IDs (e.g., BotRegistry, buff/infection maps, DeathFinalizer) would receive `"_"` as the entity ID, silently producing ghost entries. The comment "Covers two cases" is correct, but case 2 — "Spring integration tests that place entities via worldGrid.setEntity() without registering them" — is risky: those tests may not notice that lifecycle hooks are silently firing on `"_"`.

**Fix:** Guard the hooks in `processDeaths` (and any other call site that reads `entry.entityId()`) against the sentinel value, or change the fallback to pass `null` so callers can filter explicitly:
```java
// In processDeaths loop, after obtaining entry from fallback path:
if ("_".equals(entry.entityId())) continue; // no lifecycle hooks for back-compat synthetic entries
```
Or better: in the fallback, only produce entries for the purely-positional use cases (processInteractions, processOvercrowding) and never use them in processDeaths where lifecycle hooks fire.

---

## Info

### IN-01: `TickEngine.@EventListener(ApplicationReadyEvent.class)` change is safe but undocumented

**File:** `src/main/java/com/paralife/engine/TickEngine.java:60-65`

**Issue:** The tick engine's startup was moved from `@PostConstruct` to `@EventListener(ApplicationReadyEvent.class)`. This is correct — it avoids starting the tick loop before all beans are wired. The phase context called this out as a critical invariant to verify. The change is verified safe: `ApplicationReadyEvent` fires after the full Spring context is refreshed and all `@PostConstruct` hooks have run, so `EligibleCellIndex.initialize()` and `EnvironmentEngine.registerAsCompostSink()` are both complete before the first tick fires. No issue. Noting here because CLAUDE.md's Architecture section still says `TickEngine.@PostConstruct` — that documentation is now stale.

**Fix (doc only):** Update CLAUDE.md entry for `TickEngine` entry point from `@PostConstruct` to `@EventListener(ApplicationReadyEvent.class)`.

---

### IN-02: `ActionResolver.clearStateForTest()` and `TickBroadcaster.clearStateForTest()` are production-visible public methods with no production guard

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:205-210`, `src/main/java/com/paralife/websocket/TickBroadcaster.java:184-186`

**Issue:** Both methods are `public` and documented as test-only. A production caller invoking `clearStateForTest()` on `ActionResolver` would wipe `lastReproducedTick` and `compositeTicksSinceMove`, allowing entities to bypass reproduction cooldowns. The methods are not annotated, and IDEs/static analysis cannot distinguish them from production API. The risk in practice is low (no caller other than the test exists), but the pattern establishes a precedent where test seams silently corrupt production state.

**Fix:** Add `@VisibleForTesting` (Guava annotation, already a transitive dep) or a `/* test-only */` Javadoc warning per CLAUDE.md conventions. Alternatively, scope to package-private (but GoldenTraceEquivalenceTest is in the same package, so that works for the test side). No code change required for correctness.

---

### IN-03: `LiveEntityRegistry` O(N) sort in `snapshot()` is called multiple times per tick from `SimulationEngine`

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:259-282`, `src/main/java/com/paralife/engine/LiveEntityRegistry.java:149-153`

**Issue:** `entitySnapshot()` (SimulationEngine) is called 4–5 times per tick: `processInteractions` (×3 for particles, composite members, bonded pairs), `processEnergyDecay`, `processOvercrowding`, `processDeaths` (×2). Each call invokes `liveEntityRegistry.snapshot()` which allocates a new `ArrayList` + sort O(N log N). At N=256 this is negligible (~µs), as the Javadoc notes. At Phase 21's target density (N=1000+) this becomes 6–8 allocations × O(N log N) per tick, each also acquiring the `synchronized` lock. Consistent with Phase 21 revisit plan, but the multi-call pattern is a refactoring opportunity: capture the snapshot once at the top of `processTick` and pass it down, eliminating duplicate sorts and lock acquisitions.

**Fix:** Pre-compute at `processTick`:
```java
public void processTick(long tickNumber) {
    int width = worldGrid.getWidth();
    int height = worldGrid.getHeight();
    List<LiveEntityRegistry.EntityEntry> entityList = entitySnapshot(width, height); // once
    ...
    processInteractions(width, height, tickNumber, entityList);
    processEnergyDecay(width, height, tickNumber, entityList);
    ...
}
```
This is a Phase 21 pre-optimisation candidate, not a current bug. No correctness impact.

---

_Reviewed: 2026-05-01_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
