---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-03T17:42:47Z
files: ["src/main/java/com/paralife/engine/EligibleCellIndex.java", "src/main/java/com/paralife/engine/LiveEntityRegistry.java", "src/main/java/com/paralife/engine/EntityLifecycleListener.java", "src/main/java/com/paralife/engine/SimulationEngine.java", "src/main/java/com/paralife/engine/EnvironmentEngine.java", "src/main/java/com/paralife/engine/ActionResolver.java", "src/main/java/com/paralife/engine/DeathFinalizer.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/engine/BotRegistry.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/ResumeTokenRegistry.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/main/java/com/paralife/bot/BotClient.java", "src/main/java/com/paralife/codec/Event.java", "src/main/java/com/paralife/codec/PerceptionCodec.java", "src/test/java/com/paralife/engine/EligibleCellIndexTest.java", "src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java", "src/test/java/com/paralife/engine/PlacementDeterminismTest.java", "src/test/java/com/paralife/engine/EntityListIterationTest.java", "src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java", "src/test/java/com/paralife/engine/GoldenTraceCapture.java", "src/test/java/com/paralife/engine/CompositeFormationTest.java", "src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java", "src/test/java/com/paralife/metrics/EmergenceMetricsWiringTest.java", "src/test/java/com/paralife/admission/OutboundSenderTest.java", "src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java", "src/test/java/com/paralife/websocket/RegisterAtomicityTest.java", "src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java", "src/test/resources/golden-trace-phase19.json"]
usage:
  claude: { input: 10, output: 16, cached: 40450, tool_calls: 0, elapsed_s: 414.0 }
  gemini: { input: 251356, output: 1302, cached: 0, tool_calls: 0, elapsed_s: 280.2 }
  codex: { input: 867200, output: 8522, cached: 693120, tool_calls: 14, elapsed_s: 180.1 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 606.9 }
synthesizer: claude
synthesized_at: 2026-05-03T17:42:47Z
---

# Cross-AI Review

## Claude Review

# Pass-4 review findings

Three prior passes hammered registry/identity/cache surfaces. I focused on:
1. cross-wave RNG ordering through pending-grants drain
2. snapshot maps keyed by stale entityId post-remap
3. claimed-shipped fixes that did not actually ship
4. A1 close-then-interrupt completeness

Findings below grouped by severity. Silence on focus areas called out explicitly at end.

---

## HIGH

### H-1 — `PerceptionCodec.validateEventCode` rejects `'B'`; pass-3 F2 claim is false

**Location:** `src/main/java/com/paralife/codec/PerceptionCodec.java:639-644`

```java
private static void validateEventCode(char code, int pos) {
    switch (code) {
        case 'E', 'A', 'H', 'T', 'M', 'R', 'L', 'N', 'S', 'D' -> { /* ok */ }
        default -> throw new CodecException("Unknown event code '" + code + "' at " + pos);
    }
}
```

**What's wrong.** Pass-3 F2 stated `'B'` was added to `validateEventCode` and round-trips. It was added to `Event.java:18-19` (encode side) but **not** to `PerceptionCodec.validateEventCode` (decode side). Encode emits `v|B`; decode throws `CodecException` on the same byte stream.

`eventHasMagnitude(...)` already maps unknown codes to `false` via the default branch, so it would handle `'B'` correctly if validation passed — but validation rejects it first.

**Why it matters.** End-to-end break of the entire E1 absorbed-frame mechanism. Sequence at runtime:
1. `SimulationEngine` bond formation → `botRegistry.absorbBySession(preySession, …)` queues `AbsorbedNotice`.
2. `TickBroadcaster.drainAndBroadcastAbsorptions` builds frame with `Event('B', empty, empty)` and calls `outboundSender.offer`.
3. `OutboundSender.drainLoop` calls `PerceptionCodec.encode` → succeeds (encode does no validation, just appends `ev.code()`).
4. Wire bytes hit `BotClient.handlePayload` → `PerceptionCodec.decode` → `parseEvent` → `validateEventCode('B', …)` → throws `CodecException`.
5. `BotClient.handlePayload` catches: `log.warn("Failed to decode frame: {}")` and returns. `'B'` event never reaches `BotClient.onTick`'s `handleEntityTerminated("absorbed")` check at `BotClient.java:362-363`.
6. Prey session: server-side entity gone (BotRegistry unregistered, ATTR_ENTITY_ID cleared by `markDead`), client never enters respawn FSM, session sits idle until WS keepalive eventually drops it. No respawn ever fires for any prey-of-bond session.

This is the exact failure the F2 follow-on shipped to prevent. It's currently shipping broken.

**Suggested fix.** Add `'B'` to `validateEventCode` and `eventHasMagnitude` (the latter already returns false via default — just be explicit so future audits don't miss it):

```java
case 'E', 'A', 'H', 'T', 'M', 'R', 'L', 'N', 'S', 'D', 'B' -> { /* ok */ }
```

```java
case 'N', 'S', 'D', 'B' -> false;
```

Add a `PerceptionCodecTest` case round-tripping `Event('B', empty, empty)` so the convention from F2 ("every new event code in Event.java requires a PerceptionCodecTest round-trip case") actually has CI teeth. See L-1 below.

---

### H-2 — `randomBuff()` ordering non-deterministic via `pendingGrants` insertion order; pass-2 dismissal incorrect

**Location:**
- `EnvironmentEngine.java:tickBuffsAndInfections` lines around `for (Map.Entry<String, Infection> e : new ArrayList<>(infections.entrySet()))`
- `EnvironmentEngine.java:processPendingGrants` snapshot drain
- `EnvironmentEngine.java:randomBuff` uses `rng.nextInt(BuffType.values().length)`

**What's wrong.** Pass-2 dismissed F4 with the rationale "commutativity holds and GoldenTrace digest stays stable across runs." Commutativity holds for **DoT damage** (independent per-entity additive effects). It does **not** hold for the survivor-buff grant path:

1. `tickBuffsAndInfections` iterates `infections.entrySet()` snapshot (HashMap, non-deterministic order).
2. For each entry whose decremented infection `isExpired()`, calls `envCleanupHooksBean.addPendingGrant(...)`. Insertion order into the list = HashMap iteration order = JVM/build/load-factor dependent.
3. `processPendingGrants` snapshots the list and drains in that order. For each grant on a solo `Particle`/`BondedPair`, `grantSurvivorBuffs` calls `randomBuff()`, which consumes `rng.nextInt(BuffType.values().length)`.
4. Two entities curing on the same tick → two `randomBuff()` calls in a non-deterministic order → entity X gets buff A in run 1 and buff B in run 2 (the deltas swap).

Pass-2's "commutativity" argument applied only to `applyDoTDamage` in the same loop, not to the buff-grant path that consumes RNG.

**Why it matters.** Violates the D-06 single-threaded determinism contract under any scenario where ≥2 entities cure on the same tick. Currently inert because:
- `GoldenTraceEquivalenceTest` runs 200 ticks at moderate density; mutagen events are gated by Poisson + WINTER off-season + outbreak lifetime so simultaneous multi-cure on one tick is unlikely.
- `EnvironmentDeterminismTest` (per `onTickEnvOnlyForTest` Javadoc) explicitly excludes composites and runs short scenarios.

But: any future test, replay, or production reproducibility claim that exercises a cure cluster will diverge across runs while leaving zero indication of why. F4 partial flagged exactly this; the pass-2 dismissal closed it on the wrong grounds.

**Suggested fix.** Sort the infection-entry iteration by entityId before deciding expiry, OR sort `pendingGrants` by `entityId` inside `processPendingGrants` before the drain loop. Either gives a deterministic `randomBuff()` call sequence under the single-threaded contract:

```java
// In tickBuffsAndInfections, replace the entrySet().iterator() loop with:
List<Map.Entry<String, Infection>> sorted = new ArrayList<>(infections.entrySet());
sorted.sort(Map.Entry.comparingByKey());
for (Map.Entry<String, Infection> e : sorted) { ... }
```

Or in `processPendingGrants`:

```java
synchronized (grants) {
    snapshot = new ArrayList<>(grants);
    grants.clear();
}
snapshot.sort(Comparator.comparing(PendingGrant::entityId));
```

Either fix is one line. Add an `EnvironmentDeterminismTest` case that infects two entities on the same cell+tick, advances to cure, and asserts the granted buff types match across two seeded runs.

---

## MEDIUM

### M-1 — `AdmissionMetrics.bucketTagsByEntityId` leaks original entityId snapshot through every H-A remap

**Location:**
- `AdmissionMetrics.java:incActiveBucket` (writes `bucketTagsByEntityId.put(eid, tags)` keyed by ATTR_ENTITY_ID at register time)
- `AdmissionMetrics.java:releaseBucketTags`
- `WorldWebSocketHandler.java:cleanupBot` reads ATTR_ENTITY_ID (post-remap) then calls `lookupBucketTags(entityId)` and `releaseBucketTags(entityId)`
- `WorldWebSocketHandler.java:onEntityRemapped` updates ATTR_ENTITY_ID but does NOT migrate the metrics snapshot

**What's wrong.** Per H-A, `onEntityRemapped` updates `session.getAttributes().put(ATTR_ENTITY_ID, newEntityId)` and rewrites resume-token registry entries. It does **not** migrate the `bucketTagsByEntityId` snapshot in `AdmissionMetrics` from `oldEntityId → newEntityId`.

End-of-life cleanup path:
- `cleanupBot` reads ATTR_ENTITY_ID (post-remap) → `entityId = "bp-…"` (or composite member id).
- `lookupBucketTags("bp-…")` → null (snapshot was keyed by the original `"entity-session-N"`).
- Falls through to session-derived tags for the active-bucket dec (works).
- `releaseBucketTags("bp-…")` → no-op (key absent from map).
- The original `"entity-session-N" → tags` entry stays in `bucketTagsByEntityId` permanently.

`cleanupByEntityId` (grace expiry path) hits the same problem — it receives the post-H-C-remapped entityId from the resume registry callback, never the original.

**Why it matters.** Under any workload that exercises bond formation + composite formation, every formation event leaks one `bucketTagsByEntityId` entry per identity transition (bond → bp.id; composite formation → 2× cm.id; revert → 1× bp.id; dissolve → N× particle id). Slow leak — sub-1 KB per formation — but unbounded and operator-invisible. Phase 18's stated 5 000-connections-per-JVM ceiling means this map will grow proportional to formation churn over the JVM lifetime.

Pass-3 caught the analogous `ResumeTokenRegistry` leak (F3, which closed on `markDead`). The same H-A remap pattern wasn't audited against `bucketTagsByEntityId`.

**Suggested fix.** Have `onEntityRemapped` rewrite the snapshot:

```java
@Override
public void onEntityRemapped(String sessionId, String oldEntityId, String newEntityId) {
    if (resumeTokenRegistry != null) {
        resumeTokenRegistry.remapEntity(oldEntityId, newEntityId);
    }
    if (admissionMetrics != null) {
        admissionMetrics.remapBucketTags(oldEntityId, newEntityId);
    }
    WebSocketSession session = sessionRegistry.getSession(sessionId);
    if (session == null) { ... }
    session.getAttributes().put(ATTR_ENTITY_ID, newEntityId);
}
```

Add `AdmissionMetrics.remapBucketTags(String, String)`:
```java
public void remapBucketTags(String oldEntityId, String newEntityId) {
    if (oldEntityId == null || newEntityId == null || oldEntityId.equals(newEntityId)) return;
    Tags existing = bucketTagsByEntityId.remove(oldEntityId);
    if (existing != null) bucketTagsByEntityId.put(newEntityId, existing);
}
```

Add a metrics-leak invariant test in `AdmissionMetricsLifecycleTest` that drives 100 bond formations + cleanups and asserts `bucketTagsSize() == 0` at the end.

---

## LOW

### L-1 — Convention violation: `'B'` event code added to `Event.java` without `PerceptionCodecTest` round-trip

**Location:** Convention named in pass-3 F2: "every new event code in `Event.java` requires a `PerceptionCodecTest` round-trip case." No test exists for `'B'` (and no test would have passed — see H-1).

**Why it matters.** Pass-3 claimed F2 shipped, but no CI gate enforced the encode/decode symmetry. The very class of bug the convention exists to prevent shipped through three pass reviews.

**Suggested fix.** Add to `PerceptionCodecTest`:
```java
@Test
void absorbedEventBRoundTrips() {
    Frame.TickFrame f = new Frame.TickFrame(1L, 0, 0, 0, 0, 0, List.of(), Optional.empty(),
            List.of(), List.of(new Event('B', Optional.empty(), OptionalInt.empty())),
            Optional.empty(), List.of());
    String wire = PerceptionCodec.encode(f);
    Frame round = PerceptionCodec.decode(wire);
    assertThat(round).isEqualTo(f);
}
```

Promote the convention to a code-level grep gate in CI (e.g., a `PerceptionCodecConventionTest` that iterates `Event.java`'s switch and asserts each code has a round-trip test).

---

### L-2 — `OutboundSender.FrameEmitListener` invoked inside `synchronized(session)` is a footgun for future test expansion

**Location:** `OutboundSender.java:drainLoop`, the `emitListener.onEmit` call inside the synchronized block.

**What's wrong.** Per the JavaDoc the placement is intentional ("so the post-drain `synchronized(session)` barrier in tests covers in-flight callbacks"). But it means a misbehaving listener (blocks, deadlocks, awaits a CDL, etc.) holds the session monitor and stalls every other writer (keepalive PING, out-of-band stall/error frames). The catch is only `Exception` per LOW-10 — which is correct for OOM/SOE, but doesn't help with a blocking callback.

**Why it matters.** Today's only listener is `GoldenTraceCapture.onEmit` which does a synchronized hashmap update + `MessageDigest.update` — fast and non-blocking. But the test seam is documented as a generic `FrameEmitListener` — a future test that awaits a latch from inside `onEmit` (e.g. to coordinate frame ordering with another harness) would deadlock the session monitor with no obvious failure mode.

**Suggested fix.** Either:
1. Document the contract on the interface explicitly: "MUST NOT block, MUST NOT call any method on the session, MUST NOT acquire any lock that any writer holds."
2. Move the listener call OUTSIDE `synchronized(session)` and have tests insert their own barrier (e.g., a `CountDownLatch` per emit set in `onEmit` and awaited by the test).

Option 2 is cleaner long-term but breaks `GoldenTraceEquivalenceTest`'s current barrier. Option 1 is a one-line Javadoc patch and acceptable for now given the single in-tree user.

---

## Focus area silence

Per the prompt's "silence is ambiguous" rule:

- **Cross-wave interactions (Q1).** Walked Order 10 → 14 → 20 → 25 → 50. `cellStatusCache`/`entityStatusCache` volatile-snapshot publication is sound (writer mutates fresh staging; published view is read-only via `Collections.unmodifiableMap` wrap; reader sees stable snapshot). `EligibleCellIndex.notifyChanged` always fires AFTER the WorldGrid mutation, lock-order respected. SimulationEngine sees the prior tick's `cellStatusCache` (Order 10 < 14) — by design, splash-damage path uses `toxinGrid` directly. **No additional cross-wave bug found beyond H-2.**

- **Lifecycle hook coverage (Q2).** All 13 structural mutation sites traced through SimulationEngine (bond, composite formation, revert, dissolve, panic-zone), ActionResolver (move, reproduce, composite movement, REPRODUCER bud), DeathFinalizer (particle, bonded, composite-member), and WSH (handleRegister, cleanupBot, cleanupByEntityId). Each updates `LiveEntityRegistry` + `BotRegistry` + `EligibleCellIndex` + `ResumeTokenRegistry` (where applicable). **No missed registry hook found beyond M-1's `bucketTagsByEntityId` migration gap.**

- **EntityId remap correctness (Q3).** `H-A` (BotRegistry/ATTR_ENTITY_ID) + `H-B` (DeathFinalizer bp.id() unregister) + `H-C` (ResumeTokenRegistry.remapEntity) form a consistent triangle. `ResumeTokenRegistry.remapEntity` iterates a CHM with weakly-consistent semantics but the WS:entity 1:1 invariant means no concurrent token issuance can race the remap (only stall→reconnect issues new ACTIVE tokens, and that requires the WS to close first, which can't happen mid-tick-handler). The leak case is M-1 above; no remap correctness bug found.

- **Determinism under stress (Q4).** Beyond H-2 (`randomBuff()` ordering): the `previousPoolEnergy` map and `compositeRegistry.getAll()` non-deterministic iteration are documented as inert (`L1` comment in SimulationEngine.processDeaths). `M-A` stable CHM drain + `M-B` sessionId-sorted ordering close the action-resolution paths. No further determinism violation found.

- **Test coverage gaps (Q5).** L-1 is the most likely bug-slip vector. The `GoldenTraceEquivalenceTest` extension to exercise ActionResolver verbs via deterministic action scripts was already known from pass-3.

- **OutboundSender A1 (Q6).** The close-then-interrupt fix is complete. `detachSession(WebSocketSession)` closes transport before interrupt; Jetty's blocking `sendMessage` unblocks with `IOException`; drain VT catches, loops, `queue.take()` throws `InterruptedException`, exits. No path observed where the VT could still hold `synchronized(session)` after detach returns. `FrameEmitListener` does not fire for queued sends after interrupt — correct behaviour for production close (frames lost on close is by design); tests using `GoldenTraceCapture` correctly call `awaitAllSessionQueuesDrained` BEFORE detach. Regression is covered by `OutboundSenderTest.detachSessionWithSessionRefUnblocksInFlightSend` (asserts elapsed < 200 ms via `CloseAwareSession` — fails under the original hang without depending on the absolute timeout value beyond the join contract).

---

## Gemini Review

Here is the pass-4 cross-AI code review for Phase 19 + 19.5 + Pass-2 follow-ups + P22 A1. I have focused exclusively on deep cross-wave interactions, determinism, lifecycle coverage gaps, and the A1 fix completeness.

### HIGH Severity

**1. EnvironmentEngine CHM Iteration Non-Determinism Destroys Global RNG**
- **Location:** `EnvironmentEngine.java:493` (`tickBuffsAndInfections`) and `EnvironmentEngine.java:551` (`processPendingGrants`).
- **What's wrong:** `tickBuffsAndInfections` iterates over `infections.entrySet()`, which is backed by `ConcurrentHashMap` with non-deterministic iteration order. Expiring infections are added to `pendingBuffGrants` in this random order. When `processPendingGrants` drains the queue, it calls `grantSurvivorBuffs` which invokes `randomBuff()` for non-composite entities. This arbitrary ordering randomizes the consumption of `rng.nextInt()`.
- **Why it matters:** Breaks the D-06 determinism contract under stress. If multiple infections expire on the same tick, the RNG sequence diverges. Any cross-run divergence in RNG state will snowball, causing entirely different simulation outcomes (combat, placements, lightning rolls) after the first simultaneous infection expiry.
- **Suggested fix:** Sort the `snapshot` list in `processPendingGrants` deterministically (e.g., by `PendingGrant.entityId`) before iterating it to assign survivor buffs.

**2. `markStalled` Re-introduces 5-Minute Hang via Stuck Socket Writes**
- **Location:** `WorldWebSocketHandler.java:509` (`markStalled`), `OutboundSender.java:125` (`detachSession(String)`).
- **What's wrong:** The A1 fix added `detachSession(WebSocketSession)` to close the transport and forcefully unblock stuck Jetty socket writes. However, `markStalled` still uses the `detachSession(session.getId())` String overload because it wants to keep the session open to deliver an out-of-band `E|408` frame. If the drain VT is blocked mid-write to a slow client (the exact scenario causing the overflow), `Thread.interrupt()` will not unblock it. The VT hangs, retaining the `synchronized(session)` monitor indefinitely. When `markStalled` subsequently calls `sendOutOfBand(session, ...)`, it blocks trying to acquire `synchronized(session)` and hangs the calling thread.
- **Why it matters:** A single slow client triggers a queue overflow, calling `markStalled` on the tick thread. Because the socket is stuck, `markStalled` hangs the tick thread for up to 5 minutes waiting for the OS socket timeout, bringing down the entire simulation. This defeats the OutboundSender's core purpose of isolating slow clients.
- **Suggested fix:** In `markStalled`, prioritize stability over reliable delivery of the 408 frame to a stuck socket. Call `session.close(CloseStatus.SERVICE_RESTARTED)` FIRST to unblock any stuck VT, then call `outboundSender.detachSession(session)`.

**3. False Confidence in A1 Fix: Test Does Not Assert VT Exit**
- **Location:** `OutboundSenderTest.java:132` (`detachSessionWithSessionRefUnblocksInFlightSend`).
- **What's wrong:** The test asserts that `detachSession(WebSocketSession)` returns quickly (`elapsedMs < 200`), but fails to assert that the interrupted drain VT actually terminates. Because `detachSession(WebSocketSession)` intentionally omits the `t.join()` call, it returns instantly regardless of the thread's state. The test will always pass even if the VT remains permanently hung in the background.
- **Why it matters:** This provides false assurance that the "shared-JVM hang" bug is fixed. If a future regression re-introduces a blocking call that ignores both `session.close()` and thread interrupts, this test will remain green while the production server silently leaks VTs.
- **Suggested fix:** The test must explicitly wait for the VT to exit. Capture the VT reference (e.g., via reflection or a package-private accessor for `senderThreads`), and explicitly call `t.join(2000)` followed by `assertThat(t.isAlive()).isFalse()`.

### MEDIUM Severity

**4. `LiveEntityRegistry` Invariant Test Lacks Movement/Reproduction Coverage**
- **Location:** `LiveEntityRegistryInvariantTest.java`.
- **What's wrong:** The integration test verifying the SCALE-07 invariant (that `LiveEntityRegistry` exactly mirrors non-rock grid occupants) only covers 4 lifecycle scenarios: AT-REST, POST-DEATH, POST-BOND-FORMATION, and POST-COMPOSITE-FORMATION. It completely omits coverage for `ActionResolver` movement, reproduction, composite movement (LOCOMOTOR IRV), and composite-member death (graceful degradation and shatter).
- **Why it matters:** Movement and reproduction are the most frequent structural mutations in the simulation. If a future refactor to `ActionResolver` forgets to call `liveEntityRegistry.updatePosition` or fails to register a child, the registry will drift from the grid. Because the invariant test ignores these paths, a critical state-corruption bug could easily slip past CI.
- **Suggested fix:** Add `registryMatchesGridOccupantsAfterMovement`, `registryMatchesGridOccupantsAfterReproduction`, and `registryMatchesGridOccupantsAfterCompositeMemberDeath` test cases.

---
*(Note: I reviewed EntityId Remap Correctness across bond formation, reversion, and dissolution and found the lifecycle mappings handle `botRegistry`, `liveEntityRegistry`, and `entityLifecycleListener` cleanly. The `OutboundSender` FrameEmitListener behavior is correct: if `sendMessage` is aborted by transport closure, the `IOException` correctly skips the listener callback, preventing dropped frames from muddying the golden trace.)*

---

## Codex Review

**HIGH**

1. **Location**: [PerceptionCodec.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/codec/PerceptionCodec.java:683), [Event.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/codec/Event.java:18), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:304)  
   **What’s wrong**: `Event` accepts `'B'`, and `TickBroadcaster` emits `v|B`, but `PerceptionCodec.validateEventCode` still rejects `'B'`; `eventHasMagnitude` also omits it from the no-magnitude set.  
   **Why it matters**: Prey sessions receiving the absorbed terminal frame cannot decode it, so `BotClient` never enters the respawn path after bond absorption.  
   **Suggested fix**: Add `'B'` to `validateEventCode` and treat it like `'D'` in `eventHasMagnitude`; add the required encode/decode round-trip test for `v|B`.

2. **Location**: [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:944), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:263), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:311)  
   **What’s wrong**: `markDead` only removes `ATTR_ENTITY_ID`; it does not clear the entity’s ACTIVE resume token or remove `ATTR_RESUME_TOKEN`.  
   **Why it matters**: Every death or absorbed-prey respawn leaves an ACTIVE `ResumeTokenRegistry` entry behind forever. ACTIVE entries never expire, so long-running churn grows `tokenMap` unbounded.  
   **Suggested fix**: In `markDead`, capture the old entity id, call `resumeTokenRegistry.clearActive(oldId)`, and remove `ATTR_RESUME_TOKEN`. Add a death/absorbed respawn test that asserts old active tokens are gone.

**MEDIUM**

1. **Location**: [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:723), [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:766), [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:815)  
   **What’s wrong**: The code dismisses `ConcurrentHashMap` infection iteration order as commutative, but expired infections enqueue survivor grants, and solo grants consume `rng` via `randomBuff()`. The order therefore determines which entity receives which random buff.  
   **Why it matters**: With fixed seeds, two same-tick solo infection expiries can assign different buff sequences to entity ids if CHM iteration order changes, affecting movement/attack/perception and golden traces.  
   **Suggested fix**: Sort infection entries, or sort pending grants by stable key before calling `grantSurvivorBuffs`. Add a two-entity same-tick cure test asserting exact entity→buff mapping.

2. **Location**: [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:830), [AdmissionMetrics.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:239), [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:875)  
   **What’s wrong**: Entity remap updates resume tokens and session attrs, but not `AdmissionMetrics.bucketTagsByEntityId`. The metrics snapshot remains keyed by the old entity id.  
   **Why it matters**: Bond/composite remap followed by cleanup under the new id cannot find the original bucket tags. Active/stalled gauges can be decremented in the wrong bucket or not decremented at all, and old snapshot entries leak.  
   **Suggested fix**: Add `AdmissionMetrics.remapBucketTags(oldId, newId)` and call it in `onEntityRemapped` before the session lookup/early return.

3. **Location**: [SimulationEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:831), [SimulationEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:1298), [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:1712)  
   **What’s wrong**: `FLEEING` is keyed by entity id and has a transfer helper, but composite formation `bp -> cm` and dissolution `cm -> particle` remap ids without transferring or explicitly clearing `FLEEING`.  
   **Why it matters**: The f-block can silently drop a still-active fleeing effect after identity remap, while the old map entry leaks until expiry.  
   **Suggested fix**: Transfer `FLEEING` at every identity-preserving remap, or deliberately clear it at cleanse boundaries with an explicit helper and tests.

**LOW**

1. **Location**: [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:183), [OutboundSenderTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/OutboundSenderTest.java:156)  
   **What’s wrong**: The A1 regression test proves `detachSession(WebSocketSession)` returns quickly and calls `close`, but because the method no longer joins, the test does not prove the sender VT actually exited or released `synchronized(session)`.  
   **Why it matters**: A regression that leaves the VT blocked or parked after close can still pass CI while leaking one VT per dropped session.  
   **Suggested fix**: Add a package-private sender-thread liveness probe or a fixture latch that asserts the in-flight send exits and the old VT terminates after detach.

**No New Finding**

I did not find a JMM publication bug in the current `cellStatusCache` / `entityStatusCache` snapshots: the volatile swaps publish completed maps, and the published cell map is not mutated again after handoff. I also did not find a new blocker in `LiveEntityRegistry.snapshot()` mid-iteration behavior; the handlers generally re-read the grid occupant and skip stale snapshot entries safely. Eligible-cell dirty-bbox hooks also appear to run after structural grid mutations on the reviewed paths.

---

## Opencode Review

I'll conduct a line-by-line adversarial review across all seven focus areas. Here are my findings:

---

## HIGH

### H1 — `updateBotRegistryForFormation` still queues spurious DeathNotice (F1 residual)

**Location:** `SimulationEngine.java` `updateBotRegistryForFormation` method, which calls `botRegistry.unregisterByEntity(bp.id())` then `botRegistry.register(...)`.

**What's wrong:** `BotRegistry.unregisterByEntity` unconditionally adds a `DeathNotice` to `deathsThisTick` (at `BotRegistry.java:181`). This DeathNotice is then drained by `TickBroadcaster.drainAndBroadcastDeaths` at Order(50) and a terminal `v|D` frame is sent to the composite member's controlling session. The session's entity (the newly formed CompositeMember) is alive — the client receives a death event for a living entity and triggers the respawn FSM, losing its entity on the next `r|` frame. The F1 fix in pass-3 claimed to have eliminated this, but the `unregisterByEntity` + `register` pair remains.

**Why it matters:** Under any scenario where `canFormComposites=true` and two adjacent BondedPairs merge (e.g. 32×32 grid with 6 catalyst/spore clusters as in `CompositeFormationDeterminismTest`), every composite formation silently sends a spurious death frame to each constituent session. The bot client discards its live entity and re-registers a fresh particle — population counts diverge silently from the server's view.

**Suggested fix:** Replace `unregisterByEntity` + `register` with a single `remapEntity(sessionId, newMemberId, pos)`:
```java
botRegistry.getSessionForEntity(bp.id()).ifPresent(sessionId -> {
    botRegistry.remapEntity(sessionId, newMemberId, pos);
    if (entityLifecycleListener != null) {
        entityLifecycleListener.onEntityRemapped(sessionId, bp.id(), newMemberId);
    }
});
```

---

### H2 — `randomBuff()` consumes seeded RNG in non-deterministic grant order (F4 deferred)

**Location:** `EnvironmentEngine.java` — `tickBuffsAndInfections` iterates `infections.entrySet()` (ConcurrentHashMap, non-deterministic order) → `processPendingGrants` drains in insertion order → `grantSurvivorBuffs` → `randomBuff()` → `rng.nextInt()`.

**What's wrong:** The pass-2 rationale dismissed CHM-iteration determinism concerns as "DoT is commutative, order doesn't matter." That reasoning covered DoT damage application but did NOT cover `randomBuff()`, which consumes the shared `EnvironmentEngine.rng`. When grants are queued in non-deterministic CHM iteration order, `grantSurvivorBuffs` calls `rng.nextInt()` in a different sequence across runs with the same seed. The buff type assigned to each entity differs → different combat/movement/upkeep modifiers → different simulation outcomes → different per-session digests. The `GoldenTraceEquivalenceTest` sets `events.seed=42` and events are enabled (no override to `false`), so this is a latent determinism regression waiting for the first mutagen infection to fire during the 200-tick window.

**Why it matters:** Scenario: seed=42, mutagen outbreak hits at tick 50, infects 3 entities. CHM order varies across JVM invocations → grants queued in different order → `rng.nextInt(all.length)` returns different values → entities get different survivor buffs. One run gives Catalyst ATTACK_PLUS_1 (survives combat, kills a Spore neighbor), another gives SENSOR_PLUS_1 (no combat help, dies). The per-session digest diverges. Golden gate passes sometimes and fails other times — flaky test.

**Suggested fix:** Sort `infections.entrySet()` before queueing grants, or sort `pendingGrants` before processing:
```java
// In tickBuffsAndInfections:
List<Map.Entry<String, Infection>> sorted = new ArrayList<>(infections.entrySet());
sorted.sort(Map.Entry.comparingByKey());
for (Map.Entry<String, Infection> e : sorted) { ... }
```

---

## MEDIUM

### M1 — `PerceptionCodec.validateEventCode` rejects 'B' — encode/decode round-trip broken

**Location:** `PerceptionCodec.java` `validateEventCode` method (switch case list: `'E', 'A', 'H', 'T', 'M', 'R', 'L', 'N', 'S', 'D'`).

**What's wrong:** Pass-3 F2 claimed to add `'B'` to the codec validator, claiming "encode/decode round-trips." The fix is not present in the shipped code. `Event.java` constructor accepts `'B'` (added in Phase 19.5 E1), and `encodeVBlock` writes `'B'` to the wire. But a `BotClient` receiving a tick frame whose `v` block contains `vD B` (the mixed-death-and-absorbed tick where one session's entity died and another's was absorbed into a bond) would throw `CodecException("Unknown event code 'B'")` and drop the entire frame — losing perception, effects, pool, roster for that tick.

**Why it matters:** If a bond forms in the same tick as a normal particle death, the TickBroadcaster drain loop iterates death notices then absorption notices. A session that happened to receive the absorbed frame for its prey entity would crash-decode the `B` event and the bot would silently miss a tick. Worse: the bot's `onTick` never runs for that tick → no action queued → entity stalls for one tick.

**Suggested fix:** Add `'B'` to both `validateEventCode` and `eventHasMagnitude` (`'B'` has no magnitude, same as `'D'` and `'N'`):
```java
case 'E', 'A', 'H', 'T', 'M', 'R', 'L', 'N', 'S', 'D', 'B' -> { /* ok */ }
```

---

### M2 — Test coverage gap: ActionResolver paths entirely untested in deterministic context

**Location:** `GoldenTraceEquivalenceTest.driveScenario` registers 30 bots but never queues `ActionFrame`s via `actionResolver.queueAction`.

**What's wrong:** The golden trace exercises the full `@Order` chain from SimulationEngine(10) through TickBroadcaster(100) with real WS frames flowing through `OutboundSender.drainLoop` → `FrameEmitListener`. But no bot ever sends an `a|M|4` or `a|E|2` frame. The entire `ActionResolver.resolveActions` dispatch (move, consume, reproduce, composite FEEDER/ATTACKER/REPRODUCER/LOCOMOTOR) runs zero iterations of its action loop every tick. The `ActionResolver.clearStateForTest` call and all the Phase 19.5 M-B/M-C determinism fixes (sorted actions, deterministic childIdCounter) are wired but never stressed.

**Why it matters:** A regression in `resolveActions` — e.g. `childIdCounter` not resetting, `lastReproducedTick` leaking across runs, `Collections.shuffle` seed divergence — passes the golden gate silently because the gate only measures "did 200 ticks of idle passive simulation produce the same bytes." The first time someone adds a deterministic action-script test, it would fail.

**Suggested fix:** Add a companion `GoldenTraceWithActionsTest` that drives 3-5 bots through a deterministic action script (e.g. "every bot moves east every tick" or a fixed per-tick action sequence) and asserts byte-identical digests across two runs. This would have caught the M-B/M-C pre-fix state (where actions were processed in HashMap iteration order and childIdCounters leaked across runs).

---

### M3 — `EnvironmentEngine.processEnvDeaths` still uses full grid scan, not entity-list iteration

**Location:** `EnvironmentEngine.java` `processEnvDeaths` method — `for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)`.

**What's wrong:** `LiveEntityRegistry` Javadoc and CLAUDE.md claim EnvironmentEngine had 2 migration sites in Plan 04. The `tickBuffsAndInfections` method was migrated. `processEnvDeaths` was not — it still does an O(width×height) scan every tick where `envDamageAppliedThisTick` is true. On a 256×256 grid this is 65,536 cell reads every tick that env damage occurred (common at high entity density with toxin/mutagen/lightning).

**Why it matters:** Performance regression vector. The gate (`envDamageAppliedThisTick`) keeps idle-tick cost at zero, but active-tick cost is proportional to grid area rather than entity count. At 1000 entities on a 256×256 grid, the scan waste is ~60x (65k grid cells vs 1000 entities).

**Suggested fix:** Replace grid scan with `liveEntityRegistry.snapshot()` iteration, same pattern as the migration in `tickBuffsAndInfections`. Check `entry.position()` occupant for death (energy <= 0) and delegate to `DeathFinalizer`.

---

### M4 — `@Order` mismatch between code and CLAUDE.md

**Location:** `TickBroadcaster.java` annotated `@Order(50)`, CLAUDE.md §Architecture lists TickBroadcaster at `@Order(100)`.

**What's wrong:** The `@Order` value determines tick pipeline sequencing. If CLAUDE.md documents Order(100) but the code is Order(50), the documented pipeline is incorrect. Moreover, if PerceptionBroadcaster is a separate bean (referenced in CLAUDE.md at Order(50)), there could be two listeners at Order(50) — Spring's behavior for same-Order listeners is undefined ordering. `TickBroadcaster` at Order(50) calls `drainAndBroadcastDeaths` which calls `worldWebSocketHandler.markDead(session)`. If another Order(50) listener also reads `ATTR_ENTITY_ID`, the ordering between markDead and that read is race-dependent.

**Why it matters:** `drainAndBroadcastDeaths` marks sessions as dead (clears `ATTR_ENTITY_ID`). If `PerceptionBroadcaster` also runs at Order(50) and reads `ATTR_ENTITY_ID` for the same session, the outcome depends on Spring's within-Order dispatch order — non-deterministic across JVM versions and restarts. A bot whose session gets `markDead` before perception runs would skip perception for that tick, missing the death notification.

**Suggested fix:** Verify the `@Order` values match the documented pipeline. If PerceptionBroadcaster now lives inside TickBroadcaster (absorbed), update CLAUDE.md. If it's a separate bean, move TickBroadcaster to Order(100) or later.

---

## LOW

### L1 — `Frame.drop` on detach: frames taken from queue but never sent are silently lost

**Location:** `OutboundSender.java` `drainLoop` — `queue.take()` followed by `if (!session.isOpen()) continue`.

**What's wrong:** In `detachSession(WebSocketSession)`, the transport is closed, the queue removed from the map, and the VT interrupted. But if a frame was already taken from the queue (`queue.take()` succeeded) before the session was closed, the VT has the frame in hand and checks `!session.isOpen()` → true → continues the loop (not sending, not invoking FrameEmitListener). The interrupt flag then exits the loop on the next check. The frame is dropped with no trace. In production this is correct (tearing down), but for the GoldenTrace equivalence test, this means any frame in flight during detach IS silently lost — the `awaitAllSessionQueuesDrained` barrier plus the `synchronized(session)` barrier in the test covers today's scenario, but a future test that detaches mid-scenario (e.g. testing STALLED-pivot within the golden trace window) would see nondeterministic frame loss.

**Why it matters:** Latent test determinism regression. Not a current bug — `awaitAllSessionQueuesDrained` prevents it. A comment noting the contract would be sufficient.

**Suggested fix:** Add a comment in `drainLoop` noting that frames dequeued after session.close() are intentionally dropped, and tests must drain queues before detach.

---

### L2 — `SimulationEngine` `processNutrientSpawning` uses full grid scan with per-nutrient `notifyChanged`

**Location:** `SimulationEngine.java` `processNutrientSpawning` — `for (int x = 0; x < width; x++)` nested with `if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(x, y)` per spawn.

**What's wrong:** This is the ONLY SimulationEngine method that still uses a full grid scan instead of entity-list iteration (all others were migrated in Plan 04). Per the Plan 04 scope, this was intentional — nutrient spawning checks empty cells, which by definition have no LiveEntityRegistry entry. However, each `notifyChanged` call triggers a 5×5 dirty bbox re-evaluation (25 cells × 8 neighbor checks × 8 sub-neighbor checks = 1600 getCell calls). If nutrient spawn probability is high on a 256×256 grid, this compounds.

**Why it matters:** Performance only, not a correctness bug. The grid scan itself is O(width×height) by design (can't be entity-list-based for empty cells). The `notifyChanged` overhead per spawn is the concern, but acceptable at current scale.

**Suggested fix:** Defer to Phase 21 perf pass. Option: batch eligible-cell index re-evaluation after all nutrients are spawned (one rebuild pass), rather than per-spawn notifyChanged.

---

## Summary

| ID | Severity | Area | Finding |
|----|----------|------|---------|
| H1 | HIGH | Lifecycle hooks | `updateBotRegistryForFormation` spawns spurious DeathNotice (F1 not truly fixed) |
| H2 | HIGH | Determinism | `randomBuff()` consumes RNG in CHM iteration order (F4 not fully addressed) |
| M1 | MEDIUM | Codec | `validateEventCode` missing `'B'` — encode/decode round-trip broken (F2 lost) |
| M2 | MEDIUM | Test gaps | ActionResolver paths untested in deterministic context |
| M3 | MEDIUM | Perf/migration | `processEnvDeaths` still uses full grid scan |
| M4 | MEDIUM | Pipeline | `@Order` mismatch between TickBroadcaster code (50) and CLAUDE.md docs (100) |
| L1 | LOW | A1 fix | Frame drop on detach — test contract implicit |
| L2 | LOW | Perf | Nutrient spawning uses full grid scan + per-spawn notifyChanged |

---

## Consensus Summary

### Agreed Strengths
- A1 close-then-interrupt detach unblocks Jetty `sendMessage` quickly (claude, gemini implicitly via test scope, codex on liveness)
- Lifecycle remap triangle (H-A/H-B/H-C) coherent across BotRegistry/DeathFinalizer/ResumeTokenRegistry for the paths actually exercised (claude, codex)

### Agreed Concerns
- **HIGH — `'B'` event code decode broken: `PerceptionCodec.validateEventCode` rejects `'B'` while `Event`/`TickBroadcaster` emit it.** Pass-3 F2 claim false; prey absorbed-frame undecodable, respawn FSM never fires. Add `'B'` to `validateEventCode` + `eventHasMagnitude`, add round-trip test. (claude H-1, codex HIGH#1, opencode M1)
- **HIGH — `randomBuff()` non-deterministic via CHM iteration → pendingGrants order.** Pass-2 commutativity dismissal applied only to DoT, not to RNG-consuming survivor-buff path; breaks D-06 on any same-tick multi-cure. Sort `infections.entrySet()` or `pendingGrants` by entityId; add same-tick two-entity cure determinism test. (claude H-2, gemini HIGH#1, codex MEDIUM#1, opencode H2)
- **MEDIUM — `AdmissionMetrics.bucketTagsByEntityId` not migrated on remap.** `onEntityRemapped` updates ATTR_ENTITY_ID + resume tokens but leaks original snapshot key; `cleanupBot`/`cleanupByEntityId` no-op the release. Add `AdmissionMetrics.remapBucketTags(old,new)` + invariant test. (claude M-1, codex MEDIUM#2)
- **LOW — A1 regression test does not assert VT termination.** `detachSession(WebSocketSession)` no longer joins; current test only proves elapsed < 200 ms, leaving thread-leak regressions invisible. Add VT liveness probe / `t.join(2000)` + `isAlive()==false` assertion. (gemini HIGH#3, codex LOW#1)

### Divergent Views
- **`updateBotRegistryForFormation` spurious DeathNotice (opencode H1).** Only opencode flagged; claude explicitly cleared lifecycle-hook coverage and codex/gemini found no remap correctness bug. Likely opencode misread `unregisterByEntity` semantics (assumes it always queues a DeathNotice) — verify against current `BotRegistry.unregisterByEntity` to confirm whether formation path actually queues `'D'` for live composite members or whether there's a guard.
- **`markStalled` re-introduces 5-min hang (gemini HIGH#2).** Only gemini raised; claude explicitly cleared A1 completeness. Gemini's chain — markStalled holds session monitor via stuck VT, then `sendOutOfBand` deadlocks tick thread — depends on whether `markStalled` calls `sendOutOfBand` synchronously on the tick thread before transport close. Worth tracing `markStalled` call order against `detachSession(String)` vs `(WebSocketSession)` to confirm.
- **`@Order` mismatch (opencode M4).** Only opencode flagged TickBroadcaster Order(50) vs CLAUDE.md Order(100). Other reviewers walked the pipeline without noting it; could be a docs-vs-code drift or opencode misread. Quick `grep @Order TickBroadcaster.java PerceptionBroadcaster.java` resolves.
- **`FLEEING` not transferred on bp→cm / cm→particle (codex MEDIUM#3).** Only codex raised; claude's lifecycle-hook walk did not flag. Worth a targeted check on `SimulationEngine` formation/dissolution paths for explicit `FLEEING` transfer or clear.
- **`markDead` leaks ACTIVE resume token (codex HIGH#2).** Only codex raised as HIGH; claude noted pass-3 F3 closed the analogous leak. Likely overlap with M-1 cluster but resume-token-specific; verify whether `markDead` already calls `clearActive` or whether F3 only covered a different path.
- **Perf gaps (opencode M3 nutrient/env scan, L2).** Only opencode flagged; others treated as out-of-scope for pass-4 (Phase 21 perf work).
- **Test coverage breadth — invariant test (gemini MEDIUM#4) vs ActionResolver golden-trace (opencode M2) vs codec convention gate (claude L-1).** All three reviewers identified different uncovered surfaces; not contradictory, all worth adding.
