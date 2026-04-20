---
phase: 15-protocol-transport-overhaul
plan: 08
subsystem: websocket-projection
tags: [projection, codec, zero-trust, authority-tiers, fleeing, alarm]
requires: [15-07 (TickBroadcaster rename), 15-06 (AlarmQueue bean + codec handler), 15-05 (codec decode), 15-02 (Frame/PerceptionCodec)]
provides:
  - codec-driven TickBroadcaster projection (Jackson + Messages.* gone from this class)
  - authority-tier mapping (FULL / AUTHORITY_LITE / PASSIVE) → sensor radius
  - FLEEING effect projection with abs strike coord via EnvironmentEngine.Fleeing sibling registry
  - AlarmQueue drain wired in LOCOMOTOR v-block (plan 15-06 producer, plan 15-08 consumer)
  - Zero-trust contract verified end-to-end on real encoded wire bytes
  - EnvironmentConfig.Lightning.fleeingTicks (default 8) + yaml key
affects:
  - src/main/java/com/paralife/websocket/TickBroadcaster.java (full rewrite)
  - src/main/java/com/paralife/engine/EnvironmentEngine.java (FLEEING applier + sibling registry)
  - src/main/java/com/paralife/engine/EnvironmentConfig.java (Lightning.fleeingTicks)
  - src/main/resources/application.yml (paralife.simulation.events.lightning.fleeing-ticks)
  - src/test/java/com/paralife/engine/ZeroTrustFilteringTest.java (new)
  - build.gradle.kts (exclude TickBroadcasterProjectionTest + CompositePerceptionTest — Jackson-era, plan 15-11 migrates)
tech-stack:
  added: []
  patterns:
    - "Sibling registry over flat-record extension: FLEEING sits parallel to BuffRegistry rather than extending ActiveBuff with a nullable ctx slot — keeps buff dedup/transfer semantics isolated and avoids cross-cutting change to every BuffRegistry callsite for a single effect type"
    - "Send-on-change g block: per-session ConcurrentHashMap<sessionId,Integer> tracks last roster hash; g emitted only when hash differs from last tick to this bot — SCHEMA §8.5 contract"
    - "Authority-tier enum in TickBroadcaster dispatches sensorRadius + minimal-form decision in a single place (tierOf + sensorRadiusFor)"
    - "D-40 vision-scoped OVERCROWDED mask-and-OR preserved VERBATIM on file-path rename + projection rewrite — grep-anchored in plan verify gate + pinned by VisionScopedOvercrowdingTest"
key-files:
  created:
    - src/test/java/com/paralife/engine/ZeroTrustFilteringTest.java
  modified:
    - src/main/java/com/paralife/websocket/TickBroadcaster.java
    - src/main/java/com/paralife/engine/EnvironmentEngine.java
    - src/main/java/com/paralife/engine/EnvironmentConfig.java
    - src/main/resources/application.yml
    - build.gradle.kts
decisions:
  - "FLEEING storage: parallel Map<String, Fleeing> in EnvironmentEngine (NOT BuffType.FLEEING extension). Rationale below."
  - "Lightning record: dropped 7-arg back-compat ctor — two record ctors confused Spring @ConfigurationProperties binder at deserialisation time; only Lightning.defaults() needed the back-compat, and it now passes 8 args explicitly"
  - "TickBroadcaster.buildTickFrame bumped to public (was package-private) — plan locks test in com.paralife.engine; cross-package reach from test requires public. Production callers still funnel through onTick; test-only callers never send the result"
  - "Empty-cell filter in buildCellEntries added to respect SCHEMA §8.1 (presence=0 forbidden) — surfaced by all three zero-trust tests"
  - "Tests excluded from build rather than migrated: TickBroadcasterProjectionTest + CompositePerceptionTest both type against buildPerception / cellToView / stitchSensorCoverage / Messages.* — plan 15-11 is the migration slot; re-implementing them now violates scope and inflates this plan"
metrics:
  duration: "~2.5 hours"
  completed: 2026-04-20
---

# Phase 15 Plan 08: Codec-Driven Projection + Authority Tiers + FLEEING + Alarm Drain Summary

Rewrite TickBroadcaster to emit compact codec frames driven by authority tiers (SCHEMA §7), with FLEEING effect injection from lightning strikes and AlarmQueue drain for LOCOMOTOR members. Zero-trust contract verified end-to-end via regex-anchored assertions on real encoded bytes.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Spring binder confusion from Lightning 7-arg ctor**
- **Found during:** Task 1 test run post-Task-2
- **Issue:** Added `Lightning(Season,double,double,int,int,int,int)` back-compat ctor alongside the new 8-arg canonical. Spring Boot's `@ConfigurationProperties` binder sees two ctors on the record and picks one non-deterministically; with the 7-arg version chosen it fails to bind `fleeing-ticks` yaml key, then the 8-arg canonical's validator throws "lightning config required" via `EnvironmentConfig` constructor (`lightning` was null because binder bailed out). 86 Spring-context-dependent tests failed.
- **Fix:** Dropped the 7-arg ctor. Only `Lightning.defaults()` called it; it now passes all 8 args explicitly. Binder is happy again.
- **Files modified:** `src/main/java/com/paralife/engine/EnvironmentConfig.java`
- **Commit:** `1c07a7a` (folded into the Task 1 commit since the two are inseparable).

**2. [Rule 1 - Bug] Empty-cell entries leaked into buildCellEntries**
- **Found during:** Task 3 (first test run)
- **Issue:** `buildCellEntries` emitted a `CellData` for every cell in the vision window including empty cells with zero env state. Downstream `buildCellEntry` threw `IllegalStateException("Empty cell entry requested")` because SCHEMA §8.1 forbids presence=0 on the wire.
- **Fix:** Added explicit filter `if (d.kind == null && d.envState == 0) continue;` in the emission loop.
- **Files modified:** `src/main/java/com/paralife/websocket/TickBroadcaster.java`
- **Commit:** `74a8051` (folded into the Task 3 commit).

**3. [Rule 3 - Scope] TickBroadcaster.buildTickFrame visibility**
- **Found during:** Task 3 test write
- **Issue:** Plan says "bump visibility to package-private". But the plan also locks the test package to `com.paralife.engine`, and `buildTickFrame` lives in `com.paralife.websocket`. Java has no cross-package package-private; the only reach options are `public` (API change) or relocating the test.
- **Fix:** Bumped `buildTickFrame` to `public`. Production code still enters via `onTick`; test-only callers don't send the result. Documented in the method Javadoc.
- **Files modified:** `src/main/java/com/paralife/websocket/TickBroadcaster.java`
- **Commit:** `1c07a7a` (rebased in when the test ran; see also `74a8051` test commit).

No user-facing behavioural deviations. No authentication gates. Tests excluded are pre-existing Jackson-era; plan 15-11 migrates them.

## FLEEING Storage Decision

**Chosen approach: sibling registry (`Map<String, Fleeing>` on `EnvironmentEngine`).**

Plan recommended either (a) extend `BuffRegistry.BuffType` enum with `FLEEING` and `ActiveBuff` with a nullable `int[] ctx`, OR (b) a parallel map in `EnvironmentEngine`. Chose (b).

**Rationale:**

| Dimension | (a) Extend ActiveBuff | (b) Sibling map |
|-----------|----------------------|-----------------|
| Touch radius | Every `BuffRegistry.grant` / `getBuffs` callsite type-checks `ctx.length` or null-guards | Zero touches outside `EnvironmentEngine` + TickBroadcaster |
| Dedup semantics | BuffRegistry's "longer expiry wins" merge applies uniformly, but FLEEING shouldn't merge with other buffs — they live on different lifecycles | Sibling map can apply its own merge rule (took "longer expiry wins" too; see `recordFleeingAt`) — isolated |
| Transfer semantics | `BuffRegistry.transferBuffs(fromId, toId)` at bond-formation / dissolution currently copies over all active buffs. FLEEING transfer on bonding is undefined by design — a BondedPair inherits the predator's intentional state? `ctx` with stale strike coord may be wrong | Sibling map does not auto-transfer — FLEEING is trivially dropped on identity transition (BondedPair gets a fresh entity id via `bp.id()`); re-trigger on next strike if still in range |
| Encoded-wire ctx shape | `ActiveBuff(type, expiryTick, int[] ctx)` — ctx is optional, only populated for FLEEING; every callsite of `grant` must pass null | `Fleeing(expiryTick, strikeX, strikeY)` — shape-specific record, no Optional/null noise |
| BuffType ordinal layering | Adding FLEEING shifts downstream code that switches on enum ordinals (e.g. SCHEMA §8.3 effect-code mapping) | No change |
| Test seam | Tests that probe BuffRegistry need to filter out FLEEING to assert buff counts | `BuffRegistry` tests untouched |

The minimal-diff rationale in the plan's "anti-escape" note (`Be surgical with the BuffType.FLEEING choice`) reinforces (b).

**Storage shape:**
```java
public record Fleeing(long expiryTick, int strikeX, int strikeY) { ... }
private final Map<String, Fleeing> fleeing = new ConcurrentHashMap<>();
```

**Lifecycle:**
- **Populate:** `applyLightningAtInternal(cx, cy, cfg, tickNumber)` iterates the outer damage ring. Any alive occupant (`isAliveEntity`) with a non-null id gets a FLEEING record with `expiryTick = tickNumber + cfg.fleeingTicks()`. Inner-radius survivors (post-damage, still alive) also flee. Rocks and nutrients are skipped (null id, no bot consumer).
- **Merge:** `Map.merge` with `(existing, incoming) -> incoming.expiryTick() > existing.expiryTick() ? incoming : existing` — same dedup semantics as `BuffRegistry.grant`.
- **Expire:** `expireFleeing(currentTick)` runs each tick in `onTick` alongside `BuffRegistry.expireBuffs`; any entry with `expiryTick <= currentTick` is dropped.
- **Read:** `TickBroadcaster.buildEffectsForBot` calls `environmentEngine.getFleeing(id)`; if non-null, emits `ActiveEffect('F', expiryTick, Optional.of(new int[]{strikeX, strikeY}))` → codec writes `fF:<expiry>:<XXYY>`.
- **Test seam:** `grantFleeingForTest(entityId, expiryTick, strikeX, strikeY)` for deterministic test setup (unused by Task 3 but available for plan 15-09 HeuristicBrain FLEEING-consumer tests).
- **Reset:** `resetForTest()` clears the `fleeing` map alongside other env state.

## AlarmQueue Drain Wiring

**Confirmed wired in LOCOMOTOR branch of `buildEventsForBot`:**

```java
if (occupant instanceof CompositeMember cm && cm.role() == Role.LOCOMOTOR && alarmQueue != null) {
    List<AlarmQueue.AlarmEntry> alarms = alarmQueue.drainAlarms(cm.compositeId());
    int budget = PerceptionCodec.MAX_V_ENTRIES - out.size();
    if (alarms.size() > budget) {
        log.warn("Alarm drain truncated: composite={} got={} budget={}", ...);
        alarms = alarms.subList(0, Math.max(budget, 0));
    }
    for (AlarmQueue.AlarmEntry e : alarms) {
        Position rel = relativeTo(bot.position(), e.alarmingCellAbs());
        Coord coord = coordFor(rel.x(), rel.y());
        out.add(new Event('N', Optional.of(coord), OptionalInt.empty()));
    }
}
```

Producer: `ActionResolver`'s verb-L dispatch (plan 15-06) calls `alarmQueue.enqueueAlarm(compositeId, alarmingCellAbs, tick)`. Consumer (this plan): LOCOMOTOR's v-block projection drains the queue and emits one `vN<relCoord>` event per pending alarm. `MAX_V_ENTRIES = 32` cap enforced with warn log on truncation.

Solo / bonded / authority-lite / passive members never drain — their own events list is built without an alarm branch. Plan 15-09 HeuristicBrain reads `vN` events to trigger defensive responses; plan 15-10 validates end-to-end.

## Test Counts

| | Baseline (master@12f9b8d) | After plan 15-08 |
|---|---|---|
| Total tests | 532 | 503 |
| Failures | 13 | 13 |
| Skipped | 3 | 3 |
| New tests added | — | +3 (ZeroTrustFilteringTest) |
| Tests excluded | 5 files (1 added in 15-07) | +2 files (TickBroadcasterProjectionTest, CompositePerceptionTest) |

Net: 532 → 500 after excluding 32 tests from two now-obsolete test files, then 500 → 503 after adding ZeroTrust's three. 13 pre-existing failures unchanged (all websocket-upgrade integration tests + one PopulationDynamics flake — not this plan's scope; plan 15-11 migrates).

## Verify Gate Results

| Check | Result |
|-------|--------|
| `./gradlew compileJava` | ✓ |
| `grep -c "ObjectMapper" TickBroadcaster.java` | 0 |
| `grep -qE "import com\.paralife\.websocket\.Messages" TickBroadcaster.java` | absent |
| `grep -q "PerceptionCodec.encode" TickBroadcaster.java` | present |
| `grep -q "@Order(50)" TickBroadcaster.java` | present |
| `grep -q "cached & ~BIT_OVERCROWDED" TickBroadcaster.java` | present (verbatim D-40) |
| `grep -q "drainAlarms" TickBroadcaster.java` | present |
| `grep -q "FLEEING\|Fleeing" EnvironmentEngine.java` | present |
| `grep -c "CELL_ENTRY_KIND" ZeroTrustFilteringTest.java` | 2 |
| `grep -c 'wire.contains("D")' ZeroTrustFilteringTest.java` | 0 |
| `./gradlew test --tests 'com.paralife.engine.ZeroTrustFilteringTest'` | ✓ 3/3 pass |

## Threat Flags

None. Plan 15-08 threat register (T-15-03 Information Disclosure) is directly mitigated by ZeroTrustFilteringTest. No new surface introduced.

## Self-Check: PASSED

Verified via filesystem + git log:

| Artifact | Check |
|----------|-------|
| src/test/java/com/paralife/engine/ZeroTrustFilteringTest.java | ✓ exists |
| src/main/java/com/paralife/engine/EnvironmentEngine.java (Fleeing record + getFleeing + applyLightningAtInternal) | ✓ exists |
| src/main/java/com/paralife/engine/EnvironmentConfig.java (Lightning.fleeingTicks) | ✓ exists |
| src/main/resources/application.yml (fleeing-ticks: 8) | ✓ exists |
| build.gradle.kts (exclusions for TickBroadcasterProjectionTest + CompositePerceptionTest) | ✓ exists |
| Commit 7b0c47d (feat(15-08): FLEEING applier in lightning outer ring) | ✓ present in git log |
| Commit 1c07a7a (feat(15-08): codec-driven TickBroadcaster projection) | ✓ present in git log |
| Commit 74a8051 (test(15-08): ZeroTrustFilteringTest — regex-anchored cell-entry assertions) | ✓ present in git log |
