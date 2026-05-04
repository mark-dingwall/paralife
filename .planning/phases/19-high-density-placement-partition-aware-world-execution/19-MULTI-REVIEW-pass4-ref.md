---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-03T17:29:24Z
files: ["src/main/java/com/paralife/engine/EligibleCellIndex.java", "src/main/java/com/paralife/engine/LiveEntityRegistry.java", "src/main/java/com/paralife/engine/EntityLifecycleListener.java", "src/main/java/com/paralife/engine/SimulationEngine.java", "src/main/java/com/paralife/engine/EnvironmentEngine.java", "src/main/java/com/paralife/engine/ActionResolver.java", "src/main/java/com/paralife/engine/DeathFinalizer.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/engine/BotRegistry.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/ResumeTokenRegistry.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/main/java/com/paralife/bot/BotClient.java", "src/main/java/com/paralife/codec/Event.java", "src/main/java/com/paralife/codec/PerceptionCodec.java", "src/test/java/com/paralife/engine/EligibleCellIndexTest.java", "src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java", "src/test/java/com/paralife/engine/PlacementDeterminismTest.java", "src/test/java/com/paralife/engine/EntityListIterationTest.java", "src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java", "src/test/java/com/paralife/engine/GoldenTraceCapture.java", "src/test/java/com/paralife/engine/CompositeFormationTest.java", "src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java", "src/test/java/com/paralife/metrics/EmergenceMetricsWiringTest.java", "src/test/java/com/paralife/admission/OutboundSenderTest.java", "src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java", "src/test/java/com/paralife/websocket/RegisterAtomicityTest.java", "src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java", "src/test/resources/golden-trace-phase19.json"]
usage:
  claude: { input: 78, output: 1873, cached: 5456116, tool_calls: 29, elapsed_s: 548.5 }
  gemini: { input: 990199, output: 2668, cached: 559422, tool_calls: 22, elapsed_s: 244.5 }
  codex: { input: 3450917, output: 12569, cached: 3233024, tool_calls: 58, elapsed_s: 293.2 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 32, elapsed_s: 635.8 }
synthesizer: claude
synthesized_at: 2026-05-03T17:29:24Z
---

# Cross-AI Review

## Claude Review

Pass-4 review complete. Three HIGH, two MEDIUM, one LOW. One claimed-shipped fix (F3) actually never landed.

# Pass-4 Findings

## HIGH

### HIGH-1 — Pass-3 F3 (`markDead` resume-token leak) **never shipped** despite VALIDATED.md
**Location:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:944-947`

**What's wrong:** Prompt's "already shipped" list claims F3 is closed: *"`WorldWebSocketHandler.markDead` releases ACTIVE resume tokens to prevent unbounded `ResumeTokenRegistry` growth."* Source disagrees — `markDead` is still:
```java
public void markDead(WebSocketSession session) {
    if (session == null) return;
    session.getAttributes().remove(ATTR_ENTITY_ID);
}
```
No `resumeTokenRegistry.clearActive(...)`, no `ATTR_RESUME_TOKEN` removal. `clearActive` callers in code: `afterConnectionClosed:383` (which reads `ATTR_ENTITY_ID` *after* `markDead` already nulled it → null branch → skipped) and `cleanupBot:857` (same — entityId is null after markDead).

**Why it matters:** Every death (`drainAndBroadcastDeaths` → `markDead`) and every prey absorption (`drainAndBroadcastAbsorptions` → `markDead`) leaves an ACTIVE entry in `ResumeTokenRegistry.tokenMap` keyed by the now-dead entityId. ACTIVE entries have `expiresAtTick = Long.MAX_VALUE` so the `@Order(1)` sweep never reaps them. Across server lifetime `tokenMap` grows unbounded — every death + every bond formation = +1 stale entry. Pass-3 reviewers already validated this; the fix wasn't applied.

**Suggested fix:**
```java
public void markDead(WebSocketSession session) {
    if (session == null) return;
    var attrs = session.getAttributes();
    Object eid = attrs.remove(ATTR_ENTITY_ID);
    attrs.remove(ATTR_RESUME_TOKEN);
    if (eid instanceof String entityId && resumeTokenRegistry != null) {
        resumeTokenRegistry.clearActive(entityId);
    }
}
```

---

### HIGH-2 — Mutagen `randomBuff()` rng consumption is non-deterministic (F4 in pass-3 only partially addressed)
**Location:** `src/main/java/com/paralife/engine/EnvironmentEngine.java:715-754` (iterator order) → `:769-780` (drain) → `:815` `randomBuff()` → `:870 rng.nextInt(...)`

**What's wrong:** `tickBuffsAndInfections` iterates `infections.entrySet()` defensively-copied at line 723. `infections` is a `ConcurrentHashMap` (`envCleanupHooksBean.getInfections()`) — entrySet iteration order is unspecified. The order in which `infections.entrySet()` yields entries determines the **insertion order** of `PendingGrant`s into `envCleanupHooksBean.getPendingGrants()` (line 743). `processPendingGrants` then iterates that insertion-ordered list and calls `randomBuff()` for non-CompositeMember survivors, which consumes `rng.nextInt(BuffType.values().length)` **sequentially**.

Pass-2's dismissal at lines 718-721 says *"CHM iteration order is non-deterministic but env DoT is commutative."* True for DoT, **false for `randomBuff()`** — sequential rng draws are not commutative. Different iteration orders produce different `BuffType` assignments AND different downstream rng state for every subsequent env roll (Poisson lambda checks, mutagen origin selection, gossip mutation, etc.).

**Why it matters:** Under deterministic test conditions (single-threaded, identical insertion sequence), HotSpot CHM iteration is stable run-to-run, so `GoldenTraceEquivalenceTest` digest matches today. The instant production scheduling causes any variation in entity death order, infection insertion order, or even JVM upgrade changes CHM hash bin layout, the env-rng diverges across runs and `EnvironmentDeterminismTest` breaks. Also: any real workload with N>1 simultaneously-curing survivors yields non-deterministic survivor-buff distribution within a single run.

**Suggested fix:** Sort grants by `entityId` before draining, or convert `infections` to `TreeMap`. Minimal blast radius — sort the snapshot inside `processPendingGrants`:
```java
synchronized (grants) {
    snapshot = new ArrayList<>(grants);
    grants.clear();
}
snapshot.sort(Comparator.comparing(PendingGrant::entityId));
```

---

### HIGH-3 — `markStalled` blocks tick thread when drain VT is stuck holding `synchronized(session)`
**Location:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:687-736`; `src/main/java/com/paralife/admission/OutboundSender.java:146-161, 252-285`

**What's wrong:** A stalled-trigger flow runs entirely on the **tick thread**:
- `TickBroadcaster.onTick:222` calls `outboundSender.offer(...)` 
- `OutboundSender.offer:215-224` invokes the overflow callback synchronously
- Callback is `markStalled` (wired in `WorldWebSocketHandler.@PostConstruct:273-282`)
- `markStalled:728` calls `outboundSender.detachSession(sessionId)` — String overload, **does not close transport**
- `detachSession(String)` interrupts drain VT, `t.join(100ms)`. Per `OutboundSender.java:137-144` the doc itself acknowledges *"plain Thread.interrupt() cannot break Jetty's blocking socket write — the 100ms join times out."*
- Drain VT is stuck inside `synchronized (session) { session.sendMessage(...) }` (`OutboundSender.java:261-275`) — it holds the session monitor for as long as `sendMessage` blocks
- `markStalled:732` then calls `sendOutOfBand(session, errorFrame)` which acquires `synchronized (session)` (`WorldWebSocketHandler.java:926`)
- Tick thread blocks waiting for the stuck drain VT to release the monitor — Jetty WS idle-timeout is on the order of minutes

**Why it matters:** Slow consumer triggers stall → tick thread hangs for the full Jetty WS write timeout (typically 30s–5min). Simulation halts globally. The A1 fix adds `detachSession(WebSocketSession)` for graceful close (closes transport first → unblocks `sendMessage`), but explicitly leaves `markStalled` on the broken `detachSession(String)` path "because that path needs the session open long enough to deliver the out-of-band 408." The premise is broken — if drain VT is mid-write, the session is *already* stuck and the 408 cannot be delivered until the monitor is released anyway.

**Suggested fix:** Either close-then-best-effort-OOB (accept that 408 may not reach the slow client — they're closing anyway), or have `detachSession(String)` fall through to a transport close when the join times out. Concretely in `markStalled`:
```java
// Prefer session-aware detach: closes transport, drain VT exits via IOException, monitor freed.
if (outboundSender != null) outboundSender.detachSession(session);  // close-then-interrupt
sendOutOfBand(session, ...);  // best-effort; session.isOpen() guard already there
try { session.close(CloseStatus.SERVICE_RESTARTED); } catch (IOException ignored) {}
```
Document: under monitor-stuck conditions 408 may not reach client; the close itself is the reconnect signal.

**Test gap:** `OutboundSenderTest` covers `detachSessionWithSessionRefUnblocksInFlightSend` (the close-aware path) but has no test of `detachSession(String)` invoked while the drain VT holds the monitor — nor an integration test that drives `markStalled` with a stuck sender. Add a `CloseAwareSession`-style fixture that exercises the markStalled path end-to-end and asserts elapsed time bound + 408 delivery semantics.

---

## MEDIUM

### MEDIUM-1 — Concurrent registration can produce duplicate snapshot entries at same position → double-processing
**Location:** `src/main/java/com/paralife/engine/LiveEntityRegistry.java:96-119`; `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:556-589`

**What's wrong:** The H3 register-first-then-grid ordering plus rollback works in isolation, but `LiveEntityRegistry.register` keys only on `entityId` (per the comment at lines 97-105) — two different entityIds at the same position both succeed. Race scenario:
1. WS-thread A calls `eligibleCellIndex.sample` → returns position `P`
2. WS-thread B (different session) concurrently samples → also returns `P` (both held the monitor briefly, dense set unchanged between them)
3. A: `liveEntityRegistry.register(entityIdA, P)` — succeeds
4. B: `liveEntityRegistry.register(entityIdB, P)` — succeeds (different id)
5. Tick thread fires `@Order(10)` → `liveEntityRegistry.snapshot()` returns BOTH entries at `P`
6. `entitySnapshot()` consumers iterate, each entry calls `worldGrid.getCell(P).occupant()` — both resolve to the same Particle (whichever won `trySetEntity`)
7. Both entries are processed → winner gets double combat attacks, double energy decay, double overcrowding penalty for one tick

**Why it matters:** Real under any load harness with concurrent registrations targeting a sparse grid. Combat asymmetry is observable. Energy decay is observable. CLAUDE.md's D-06 single-threaded-reg disclaimer addresses *determinism*, not this *correctness* bug — `LiveEntityRegistry` consumers don't gracefully tolerate duplicate position entries.

**Suggested fix:** Either gate `register` on a position check (track `Map<Position,String>` and reject conflicting position registers), or have consumers de-duplicate by position when iterating snapshot:
```java
Set<Position> seen = new HashSet<>();
for (var entry : entitySnapshot(w, h)) {
    if (!seen.add(entry.position())) continue;
    ...
}
```
The position-keyed register is preferable — it surfaces the race at registration rather than papering over it.

---

### MEDIUM-2 — `EligibleCellIndex` constraint #3 is approximate under concurrent registration
**Location:** `src/main/java/com/paralife/engine/EligibleCellIndex.java:235-258`

**What's wrong:** Constraint #3 reads `worldGrid.getCell(neighborPos).occupant()` and `countOccupiedMooreNeighbours` to predict whether placing here would push an adjacent cell over `overcrowdingThreshold`. The check is point-in-time. Between `notifyChanged` returning eligible and `trySetEntity` succeeding, a concurrent placement at a neighbor cell can change the count → A places here AND B placed adjacent → A's neighbor now exceeds threshold post-hoc.

**Why it matters:** Eligibility contract guarantees no placement creates an OVERCROWDED neighbor *at sample time*. Under concurrent registration, the next-tick `processOvercrowding` discovers the violation and applies penalty — the system is self-correcting, but the contract claim ("placing here would NOT push any adjacent occupied Moore neighbour over threshold") is conditional on no concurrent placement. Same root cause as MED-1: register-first-then-place lacks atomic check-and-place against the grid.

**Suggested fix:** Documented as a deferred concern in `LiveEntityRegistry.register` comment lines 97-105; also document on `EligibleCellIndex` that constraint #3 is best-effort under concurrent registration. The real fix is atomic check-and-place against the grid (deferred to Phase 20 per existing comment), which simultaneously fixes MED-1.

---

## LOW

### LOW-1 — `OutboundSender.detachSession(String)` join-timeout has no metric, only a log line
**Location:** `src/main/java/com/paralife/admission/OutboundSender.java:152-157`

**What's wrong:** When the 100ms join times out (drain VT stuck mid-`sendMessage`), the only signal is a `log.warn`. No counter increments, no SLI. Operators have no way to alert on rising "drain VT stuck" events — relevant precisely because HIGH-3 shows tick-thread hangs flow from this state.

**Suggested fix:** Add a counter `paralife.backpressure.detach.timeouts` and increment on the `!exited` branch. Wire into existing `AdmissionMetrics`.

---

## Focus areas with NO findings (explicit silence)

- **Cross-wave snapshot invalidation (focus 1):** `LiveEntityRegistry.snapshot()` returns a defensive copy; mid-iteration mutations from death finalisers don't corrupt iteration. `cellStatusCache` volatile-snapshot publication via `Collections.unmodifiableMap` + fresh staging allocation is JMM-safe — published map is never mutated post-publish (verified `cellStatusStaging = new HashMap<>()` decouples the reference). `entityStatusCache` uses `Map.copyOf` defensive copy + clear-and-reuse staging — safe. No cross-wave race found.

- **Lifecycle hook coverage (focus 2):** All 13 mutation sites I traced (handleRegister, bond formation, composite formation, composite revert, composite dissolve, panic-zone shatter, particle death, bonded-pair death, composite-member death, solo move, composite-rigid move, reproduce, reproduce-bonus, feeder-consume, attacker-attack, reproducer-bud, cleanupBot, cleanupByEntityId, attemptPlacementForTest) consistently call `liveEntityRegistry.{register|unregister|updatePosition}` and `eligibleCellIndex.notifyChanged`. No missing site found.

- **EntityId remap correctness (focus 3):** Bond formation, composite formation, composite revert, composite dissolve all fire `entityLifecycleListener.onEntityRemapped` → `WorldWebSocketHandler` updates `ATTR_ENTITY_ID` and `ResumeTokenRegistry.remapEntity` rewrites entries. Death finalisers do NOT fire the listener (correct — entity is gone, not remapped). The single end-to-end issue is HIGH-1 (markDead doesn't release the active token), not a missed remap.

- **A1 close-then-interrupt completeness (focus 6):**
  - VT cannot still be holding `synchronized(session)` after `detachSession(WebSocketSession)` returns — the close-first ensures `sendMessage` unblocks before the method returns; if VT is still mid-monitor, it's because `sendMessage` is exiting (IOException path), and on completion it releases monitor and exits the `while` loop on the next interrupt check.
  - `FrameEmitListener` does NOT fire for in-flight frames that throw IOException (listener is called only after successful `sendMessage` at line 269) — correct behaviour for `GoldenTraceCapture`, which counts only delivered frames.
  - Regression coverage in `OutboundSenderTest.detachSessionWithSessionRefUnblocksInFlightSend` is timeout-bounded (`< 200ms`) and timeout-independent — it asserts `s.closed` and `queueDepth == -1`. Adequate for the close path. **But** there is no equivalent test for the `markStalled` path under stuck-drain-VT (see HIGH-3 test gap).

---

## Net summary

The fix surface from prior 3 passes is broad and high quality, but two latent correctness issues persist (HIGH-2 rng-order, HIGH-3 markStalled-deadlock) and one claimed-shipped fix (HIGH-1 F3) actually never landed. MEDIUM-1/MEDIUM-2 are the same root cause (lack of atomic check-and-place against the grid under concurrent registration) and will compound when Phase 20 introduces real connection multiplexing. Recommend HIGH-1 + HIGH-2 + HIGH-3 close before P20.

---

## Gemini Review

Here is the Pass-4 cross-AI code review for the Phase 19 implementation and its follow-ups.

### HIGH SEVERITY FINDINGS

**1. Dropped Action Frame on Concurrent Queue (Cross-wave interactions)**
- **Severity** — HIGH
- **Location** — `src/main/java/com/paralife/engine/ActionResolver.java` (in `drainActions()`)
- **What's wrong** — `drainActions()` iterates `pendingActions.entrySet()` and calls `pendingActions.remove(e.getKey(), e.getValue())`. If a WebSocket thread concurrently calls `queueAction(sessionId, action2)`, it overwrites the value in the map. The tick thread's atomic `remove(k, v)` call will then return `false` (because the value changed), causing the `if` block to skip adding the session to the `drained` map. The new action remains in `pendingActions` for the *next* tick, but the bot is entirely excluded from the *current* tick's action resolution.
- **Why it matters** — When a client sends a new action exactly during the tick boundary's drain window (e.g. rapid clicking or a high-frequency bot), the bot will be forced to rest for the current tick. This violates the "last-write-wins collapsed" rule, manifesting as a dropped frame/missed tick instead of gracefully processing either the old or the new action.
- **Suggested fix** — Unconditionally drain whatever action is currently mapped by key: 
  ```java
  var it = pendingActions.keySet().iterator();
  while (it.hasNext()) {
      String k = it.next();
      Frame.ActionFrame a = pendingActions.remove(k);
      if (a != null) drained.put(k, a);
  }
  ```

**2. Unbounded Buff/Infection Leak on Disconnect (Lifecycle hook coverage)**
- **Severity** — HIGH
- **Location** — `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (in `cleanupBot()` and `cleanupByEntityId()`)
- **What's wrong** — When a session gracefully disconnects or its STALLED grace window expires, the cleanup routines successfully remove the entity from the grid, `LiveEntityRegistry`, and `BotRegistry`. However, they fail to unregister the entity from `BuffRegistry` (mutagen survivor buffs) and `DeathCleanupHooks` (infections and fleeing state). These registries currently only clear state in `DeathFinalizer`, which is never invoked for disconnected sessions.
- **Why it matters** — Over the lifetime of the server, every player who disconnects while infected or holding a survivor buff leaves their state behind in `ConcurrentHashMap`s. This creates an unbounded memory leak that will eventually cause an `OOM` under high-turnover load (e.g. Phase 20/22 external load harness).
- **Suggested fix** — Expose a shared `DeathFinalizer.cleanupStructural(entityId)` helper or add `BuffRegistry` and `DeathCleanupHooks` to `WorldWebSocketHandler`, then explicitly invoke `buffRegistry.unregisterEntity(entityId)` and `hooks.clearInfectionOnDeath(entityId)` inside `cleanupBot` and `cleanupByEntityId`.

**3. Mutagen-Survivor RNG Determinism Leak (Determinism under stress)**
- **Severity** — HIGH
- **Location** — `src/main/java/com/paralife/engine/EnvironmentEngine.java` (in `tickBuffsAndInfections()` and `processPendingGrants()`)
- **What's wrong** — `tickBuffsAndInfections()` iterates `infections.entrySet()` (a `ConcurrentHashMap` with non-deterministic iteration order) and appends expiring infections as `PendingGrant` objects to a list. `processPendingGrants()` then iterates this list and calls `grantSurvivorBuffs` -> `randomBuff()`, which consumes the shared `this.rng.nextInt()`. The sequence of RNG values drawn for each entity depends entirely on the JVM's CHM iteration order.
- **Why it matters** — Under fixed-seed deterministic testing, if multiple entities survive the mutagen and cure on the same tick, the order they consume the shared RNG diverges across runs. This ripples through to subsequent RNG calls on the tick thread, breaking byte-exact placement and behavior reproducibility (violating D-06).
- **Suggested fix** — In `processPendingGrants()`, sort the `snapshot` list of `PendingGrant`s by their `entityId` before iterating and invoking `grantSurvivorBuffs`.

---

### MEDIUM SEVERITY FINDINGS

**4. Out-of-band Frames Bypass Test Capture (Test coverage gaps)**
- **Severity** — MEDIUM
- **Location** — `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (in `sendOutOfBand()`)
- **What's wrong** — `sendOutOfBand()` directly invokes `session.sendMessage(...)` inside a `synchronized(session)` block. It completely bypasses the `FrameEmitListener` callback introduced in `OutboundSender.java` for Phase 19 test capture.
- **Why it matters** — `GoldenTraceEquivalenceTest` relies entirely on `FrameEmitListener` to capture the exact stream of bytes sent to clients. Because the 408 Reconnect Required frame (sent during a STALLED transition) uses `sendOutOfBand`, it is entirely invisible to the test harness. The "golden trace" cannot verify if, when, or how backpressure frames are emitted.
- **Suggested fix** — Expose the `OutboundSender`'s `FrameEmitListener` and manually trigger it inside `sendOutOfBand()` after a successful send, or centralize the listener invocation on a wrapper around `session.sendMessage` so all outbound paths fire the test hook.

---

### Focus Area Clearances (No Issues Found)

- **Cross-wave state publication** — Beyond the `ActionResolver` bug above, the `cellStatusCache` / `entityStatusCache` publication ordering in `EnvironmentEngine` is JMM-correct. It uses `Collections.unmodifiableMap` over an abandoned `HashMap` reference (`this.cellStatusStaging = new HashMap<>()`) and `Map.copyOf` for the entity map. Parallel readers (Phase 20) will not see torn state.
- **EntityId remap correctness** — No issues found. I traced `ResumeTokenRegistry.remapEntity` and `BotRegistry.remapEntity` usage across composite formation and bond dissolution paths. The `ATTR_ENTITY_ID` updates seamlessly, the `DeathFinalizer` references correct IDs, and the STALLED tokens remap securely via in-place replacement. The identity invariants hold end-to-end.
- **The OutboundSender close-then-interrupt fix (A1)** — No issues found. The A1 fix is complete. `detachSession(WebSocketSession)` explicitly drops the session reference and issues `session.close()` before interrupting the VT. If the VT is mid-write inside `synchronized(session)`, Jetty immediately throws an `IOException`, unblocking the VT and forcing it to release the monitor. The `FrameEmitListener` safely skips firing for the interrupted frame. It eliminates the 5-minute shared-JVM hang without regressions.

---

## Codex Review

**HIGH**

**Severity** — HIGH  
**Location** — [SimulationEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:881), [BotRegistry.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/BotRegistry.java:105), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:251)  
**What’s wrong** — Composite formation still remaps live BondedPair sessions through `botRegistry.unregisterByEntity(bp.id())`, then `register(...)`. `unregisterByEntity` always queues a `DeathNotice`, so the session that just became a `CompositeMember` later receives a terminal `v|D` and `markDead()` clears its live `ATTR_ENTITY_ID`. This is exactly the remap-vs-death path that should not use death finalization semantics.  
**Why it matters** — After composite formation, surviving sessions can be told they died, become unable to act as their new composite member, and may respawn while their live member remains on the grid/registries.  
**Suggested fix** — Replace the unregister/register pair in `updateBotRegistryForFormation` with `botRegistry.remapEntity(sessionId, newMemberId, pos)` and keep `entityLifecycleListener.onEntityRemapped(...)`. Add a test asserting `botRegistry.drainDeaths()` is empty after composite formation with bound sessions.

**Severity** — HIGH  
**Location** — [PerceptionCodec.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/codec/PerceptionCodec.java:683), [Event.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/codec/Event.java:19), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:330)  
**What’s wrong** — `Event` accepts `'B'`, and the server emits `v|B`, but `PerceptionCodec.validateEventCode` still rejects `'B'`; `eventHasMagnitude` also omits it. This appears to be the claimed F2 fix, but it is not present in this checkout.  
**Why it matters** — Prey clients receiving the absorbed terminal frame cannot decode it, so `BotClient` never reaches its `ev.code() == 'B'` respawn path.  
**Suggested fix** — Add `'B'` to `validateEventCode` and to the no-magnitude branch in `eventHasMagnitude`; add an encode/decode round-trip test for `new Event('B', Optional.empty(), OptionalInt.empty())`.

**Severity** — HIGH  
**Location** — [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:944), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:263), [ResumeTokenRegistry.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/ResumeTokenRegistry.java:106)  
**What’s wrong** — `markDead()` only removes `ATTR_ENTITY_ID`; it does not clear the ACTIVE resume token for the dead/absorbed entity. Because it removes the attr, later `cleanupBot()` no longer knows which entity id to pass to `clearActive`. This also appears to be the claimed F3 fix, but it is not present here.  
**Why it matters** — Every death or absorbed prey can leave an ACTIVE resume-token entry forever. Under churn, `ResumeTokenRegistry` grows across server lifetime.  
**Suggested fix** — In `markDead`, read the entity id first, call `resumeTokenRegistry.clearActive(entityId)`, remove `ATTR_RESUME_TOKEN`, then clear `ATTR_ENTITY_ID`. Cover both `v|D` and `v|B` paths in tests.

**Severity** — HIGH  
**Location** — [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:553), [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:602), [SimulationEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:343)  
**What’s wrong** — Registration mutates `LiveEntityRegistry` and `WorldGrid` on the WebSocket thread, then only later binds `BotRegistry`, session attrs, and resume token. A tick can run in that window and mutate or remove the newly placed entity before the session is bound.  
**Why it matters** — A client can receive `S|entity...` for an entity that already died, bonded, or was absorbed without a session. This violates the stated single-threaded mutation invariant and gets worse under P20 connection load.  
**Suggested fix** — Serialize registration/cleanup structural mutations with the tick pipeline, preferably by queueing registrations onto the tick thread. A smaller stopgap is a shared simulation-mutation lock held by tick listeners and WS structural mutation paths.

**MEDIUM**

**Severity** — MEDIUM  
**Location** — [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:716), [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:770), [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:815)  
**What’s wrong** — Expired infections are iterated from a `ConcurrentHashMap`, enqueued as pending grants in that nondeterministic order, then solo/BondedPair grants consume `rng` via `randomBuff()`. The comment only reasons about DoT commutativity, not RNG-backed buff assignment.  
**Why it matters** — Under fixed seed, the same set of cured entities can receive different buff types depending on map iteration order.  
**Suggested fix** — Sort infection entries by entity id before processing expiry/grants, or sort the pending-grant snapshot before calling `grantSurvivorBuffs`.

**Severity** — MEDIUM  
**Location** — [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:405), [ToxinPathGenerator.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/ToxinPathGenerator.java:35), [EnvironmentEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:1306)  
**What’s wrong** — `spawnToxin` draws and logs a `seed`, but `ToxinPathGenerator.generatePath(...)` uses the generator’s own `new Random()` stream, which is not reset by `EnvironmentEngine.resetForTest()`.  
**Why it matters** — Fixed `paralife.simulation.events.seed` does not make toxin paths deterministic once toxin events fire.  
**Suggested fix** — Use the drawn seed to construct a per-event `ToxinPathGenerator(new Random(seed))`, or make `ToxinPathGenerator` resettable from `EnvironmentEngine.resetForTest()`.

**Severity** — MEDIUM  
**Location** — [CompositeEnergyDistributor.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:97), [CompositeRegistry.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/CompositeRegistry.java:214)  
**What’s wrong** — `CompositeEnergyDistributor` iterates `ConcurrentHashMap.values()` and consumes `compositeRng` per composite. Multiple composites make RNG consumption depend on unspecified map iteration order.  
**Why it matters** — Composite healing/drain order can diverge under fixed seed once multiple composites are active.  
**Suggested fix** — Snapshot `compositeRegistry.getAll()`, sort by `compositeId`, then process.

**LOW**

**Severity** — LOW  
**Location** — [OutboundSenderTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/OutboundSenderTest.java:156), [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:183)  
**What’s wrong** — The A1 regression test proves `detachSession(WebSocketSession)` returns quickly and removes bookkeeping, but it does not prove the sender VT actually exited.  
**Why it matters** — A regression could remove the join warning while still leaking a blocked VT after close.  
**Suggested fix** — Add a test seam or latch in `CloseAwareSession` and assert the in-flight send exits after detach, not just that detach returns fast.

No additional JMM issue found in the status-cache publication pattern itself: the published maps are not mutated after publication. The main cache risks are ordering/staleness around later death cleanup, but current readers re-derive occupants from the grid, so I would not block on that alone.

---

## Opencode Review

I'll perform a deep adversarial review across all listed files. Let me start by reading the core production files in parallel.Let me continue reading the remaining production files and testNow let me read the remaining files:Let me read the remaining critical files:Let me read the remaining handler code and key test files:Now let me check a few more files for the specific issues I'm tracking:Confirmed. Now let me compile my findings into the report.

---

## Pass-4 Cross-AI Code Review — Findings

### HIGH

**1. Composite formation queues spurious DeathNotice (F1 not actually shipped)**
- **Location:** `SimulationEngine.java:887-889` (`updateBotRegistryForFormation`)
- **What's wrong:** `updateBotRegistryForFormation` calls `botRegistry.unregisterByEntity(bp.id())` followed by `botRegistry.register(sessionId, newMemberId, pos)`. The `unregisterByEntity` call queues a `DeathNotice` for the BondedPair ID in `deathsThisTick`. At Order(50), `TickBroadcaster.drainAndBroadcastDeaths` picks up this DeathNotice, sends a terminal `v|D` frame to the controlling session, and calls `markDead(session)` — which removes `ATTR_ENTITY_ID` from the session attributes. The session then sees a DeathNotice for an entity that was merely remapped (not killed) into a CompositeMember. The bot client's respawn FSM fires, the bot re-registers a new entity, and the newly-formed CompositeMember becomes an orphan zombie — alive on the grid but with no controlling session. The prior-review claim "F1: composite formation no longer queues spurious DeathNotice (uses remap path, not unregister+register)" is contradicted by the current source: the code still uses `unregisterByEntity`+`register` rather than the existing `botRegistry.remapEntity(String sessionId, String newEntityId, Position position)` at `BotRegistry.java:169`.
- **Why it matters:** Every composite formation destroys the WS:entity 1:1 invariant for the predator's session(s). Two sessions per formation lose their entities and the composite members become unowned. Over 500+ ticks with composite-capable config, the simulation accumulates zombie CompositeMembers with no controlling session.
- **Suggested fix:** Replace the body of `updateBotRegistryForFormation` with:
  ```java
  botRegistry.getSessionForEntity(bp.id()).ifPresent(sessionId -> {
      botRegistry.remapEntity(sessionId, newMemberId, pos);
      if (entityLifecycleListener != null) {
          entityLifecycleListener.onEntityRemapped(sessionId, bp.id(), newMemberId);
      }
  });
  ```
  This preserves the `entityLifecycleListener` notification (which updates WS attributes and resume tokens) but eliminates the spurious `DeathNotice`.

**2. ResumeTokenRegistry leak on unresumed death (F3 partially unfixed)**
- **Location:** `WorldWebSocketHandler.java:944-947` (`markDead`), `ResumeTokenRegistry.java:109-115` (`clearActive`)
- **What's wrong:** `markDead` only removes `ATTR_ENTITY_ID` from session attributes. It does NOT call `resumeTokenRegistry.clearActive(entityId)`. When a bot dies and its session stays open for respawn, the old entity's ACTIVE resume token persists in the tokenMap. The `onTick` sweep at `@Order(1)` only reaps STALLED entries (where `expiresAtTick <= currentTick`); ACTIVE entries have `expiresAtTick = Long.MAX_VALUE` and are never reaped. During a respawn cycle, `handleRegister` calls `issueActive()` which creates a NEW ACTIVE entry for the new entityId, but the old entry is never removed. Over a long-running server with bots dying and respawning, the tokenMap grows without bound. Additionally, every `clearActive` call iterates the entire tokenMap (O(N)), and every graceful disconnect invokes `clearActive`, making this a perf problem as N grows.
- **Why it matters:** Unbounded memory growth in `tokenMap` (a `ConcurrentHashMap`) across server lifetime. For a 1000-bot server where each bot dies ~100 times over hours, this is ~100k stale entries. Each `clearActive` call (on every graceful disconnect) scans all of them.
- **Suggested fix:** In `markDead`, read `entityId` from session attributes before removing it, then call `resumeTokenRegistry.clearActive(entityId)`:
  ```java
  public void markDead(WebSocketSession session) {
      if (session == null) return;
      Object eid = session.getAttributes().remove(ATTR_ENTITY_ID);
      if (eid instanceof String entityId && resumeTokenRegistry != null) {
          resumeTokenRegistry.clearActive(entityId);
      }
  }
  ```

---

### MEDIUM

**3. GoldenTrace digest gate does not exercise ActionResolver**
- **Location:** `GoldenTraceEquivalenceTest.java:259-313` (`driveScenario`)
- **What's wrong:** The 200-tick scenario drives the full `@Order` pipeline (SimulationEngine at 10, EnvironmentEngine at 14, ActionResolver at 20, etc.) but never submits a single action frame. `ActionResolver.resolveActions` is never called because `pendingActions` stays empty. This means the entire action dispatch surface — movement (`M`), consume (`E`), reproduce (`R`), composite attack/feeder/reproducer, and LOCOMOTOR IRV voting — produces zero coverage in the per-session SHA-256 digest gate. A regression in ActionResolver that changes the encode path (e.g., adding/changing an `Event` in `buildEventsForBot`, emitting `v|` frames from action resolution, or changing `LiveEntityRegistry.updatePosition` calls) would not be caught by the digest gate CI.
- **Why it matters:** The digest gate is the project's strongest regression guard. A bug in ActionResolver's effect on the wire format or entity registry would pass CI silently. For example, if `resolveReproduce` spawns a child but forgets to register it in `LiveEntityRegistry`, the GoldenTrace test would not detect it because no reproduction happened.
- **Suggested fix:** Extend `driveScenario` to inject deterministic action scripts via `actionResolver.queueAction()`. At minimum, seed one bot with alternating `M`/`E`/`R` frames on a fixed tick schedule (e.g., bot-0 moves every 5 ticks). The action seed (`paralife.simulation.action-seed=42`) is already bound. Assert the per-session digests remain stable after adding the scripts, then pin a new golden baseline.

**4. Non-deterministic CHM iteration in tickBuffsAndInfections breaks replay determinism under mutagen**
- **Location:** `EnvironmentEngine.java:723` (`new ArrayList<>(infections.entrySet())`)
- **What's wrong:** `infections` is a `ConcurrentHashMap<String, Infection>` from `EnvCleanupHooksBean`. Iterating its `entrySet()` produces non-deterministic order across JVM runs. When multiple infections expire in the same tick, `processPendingGrants` drains them in this non-deterministic order, and `randomBuff()` at `EnvironmentEngine.java:871` consumes `rng.nextInt()` in that order. Since `BuffType.values().length` is > 1, different iteration orders produce different buff assignments, and more importantly, the RNG stream diverges — all subsequent RNG-dependent events (lightning strikes, gossip propagation, toxin paths) diverge too. While the `GoldenTraceEquivalenceTest` does not enable the mutagen pipeline (no `events.enabled=true` in its `@TestPropertySource`), any future deterministic test that enables mutagen would silently fail the dual-run equivalence gate because of this non-determinism.
- **Why it matters:** The F4 partial finding flagged that `randomBuff()` was not addressed in pass-2. This is the root cause. The comment at `EnvironmentEngine.java:720` ("commutativity holds and GoldenTrace digest stays stable") is correct for DoT damage only — it does NOT hold for `randomBuff()` which is not commutative under RNG consumption.
- **Suggested fix:** Sort the ArrayList copy of `infections.entrySet()` by key before iterating. The entityIds are deterministic strings; sorting them stabilises iteration order:
  ```java
  var entries = new ArrayList<>(infections.entrySet());
  entries.sort(Map.Entry.comparingByKey());
  for (Map.Entry<String, Infection> e : entries) { ... }
  ```
  This makes the `randomBuff()` RNG consumption order deterministic.

**5. TickBroadcaster at Order(50) contradicts CLAUDE.md pipeline documentation**
- **Location:** `TickBroadcaster.java:189` (`@Order(50)`) vs `CLAUDE.md` (PerceptionBroadcaster @50, TickBroadcaster @100)
- **What's wrong:** The `CLAUDE.md` architecture section describes a pipeline with `PerceptionBroadcaster @Order(50)` and `TickBroadcaster @Order(100)` as separate beans. In the actual codebase, no `PerceptionBroadcaster` class exists. `TickBroadcaster` sits at `@Order(50)` and handles both perception projection and broadcast in a single `onTick` handler. This means the "Phase 20 parallel PerceptionBroadcaster" (referenced in `EnvironmentEngine.java:192`) is a future refactor that would need to split the current `TickBroadcaster` bean — not just add a new parallel reader.
- **Why it matters:** Phase 20 planning (connection multiplexing + runtime tuning) references the `entityStatusCache` volatile snapshot as motivated by "Phase 20.1 parallel PerceptionBroadcaster." If the plan assumes PerceptionBroadcaster is a separate bean at Order(50) with TickBroadcaster at Order(100), but the current code merges both at Order(50), the split will have more blast radius than anticipated.
- **Suggested fix:** Either update `CLAUDE.md` to reflect the current merged structure, or add a forward-reference note that Order(50) is currently unified and Phase 20 must split it. No code change needed now.

---

### LOW

**6. `entitySnapshot()` called 4× redundantly in processInteractions**
- **Location:** `SimulationEngine.java:414,540,623,629` (four `entitySnapshot(width, height)` calls)
- **What's wrong:** `processInteractions` calls `entitySnapshot()` four separate times — once each for `particlePositions`, `compositeMemberPositions`, `bondedPairPositions` (×2 composite formation scan). All four snapshots capture the same registry state (no deferred writes have been applied yet). Each call triggers a full `ArrayList` copy + `O(N log N)` sort. The `LiveEntityRegistry` class Javadoc notes that snapshot is called "~8×/tick" and flags this as a candidate for a "pre-compute-once-per-tick cached snapshot" deferred to Phase 21.
- **Why it matters:** At N=1000 entities, this costs ~40µs redundant work per tick. Well within budget now, but the per-tick-cached-snapshot comment in `LiveEntityRegistry.java:158-165` was written with the 4-call pattern in mind — a future Phase 21 engineer seeing that comment might not realize one of the redundant callers is trivially consolidable.
- **Suggested fix:** Hoist `entitySnapshot(width, height)` once at the top of `processInteractions` and reuse the same list for all four scans. The deferred-write semantics guarantee no mutations happen between the snapshots. This eliminates 3 redundant sorts.

---

### Areas With No Findings

- **Cross-wave mutation invalidation:** The single-threaded tick core and deferred-write pattern in `processInteractions` prevent mutation-during-iteration issues. `EligibleCellIndex.notifyChanged` reads `Cell.flags` directly (not `cellStatusCache`), so the "stale cache" concern from the Order(10) vs Order(14) timing is correctly nullified by the unused parameter. The `cellStatusCache` and `entityStatusCache` volatile-snapshot publication uses `Collections.unmodifiableMap`/`Map.copyOf` — both safe for concurrent reads under JMM.
- **EntityId remap correctness (bond formation, revert, dissolve):** Traced end-to-end. Bond formation properly remaps BotRegistry → updates session ATTR_ENTITY_ID via listener → remaps resume tokens. Revert and dissolve follow the same pattern. No path drops a remap.
- **Lifecycle hook coverage (excluding Finding #1 above):** All 13 structural mutation sites properly update LiveEntityRegistry, EligibleCellIndex, and BotRegistry. The `cleanupCompositeMemberCellViaFinalizer` shared helper ensures consistency across the 3 death paths (combat, env, panic-zone).
- **OutboundSender close-then-interrupt (A1):** The fix is complete. `detachSession(WebSocketSession)` drops the session reference (close transport, remove queues) first, then interrupts the VT. The drain loop's `!session.isOpen()` guard after close prevents any subsequent write. The `FrameEmitListener` only fires for frames that were actually sent inside `synchronized(session)` — after close, no frames are sent, so the listener is never invoked for post-close queues. The `OutboundSenderTest.detachSessionWithSessionRefUnblocksInFlightSend` test catches the original hang regression without relying on wall-clock timeout thresholds (it measures elapsed time after close in a controlled environment).

---

## Consensus Summary

### Agreed Strengths
- A1 close-then-interrupt fix in `OutboundSender.detachSession(WebSocketSession)` resolves the original hang regression without breaking `FrameEmitListener` semantics (gemini, opencode).
- Cross-wave state publication for `cellStatusCache`/`entityStatusCache` is JMM-correct (`Collections.unmodifiableMap` over abandoned staging + `Map.copyOf`); parallel readers won't see torn state (claude, gemini, opencode).
- EntityId remap path (bond formation/revert/dissolve) end-to-end consistent: `entityLifecycleListener.onEntityRemapped` updates `ATTR_ENTITY_ID` and remaps `ResumeTokenRegistry`/`BotRegistry` correctly (claude, gemini, opencode).
- Lifecycle hook coverage across structural mutation sites is consistent (claude, opencode).

### Agreed Concerns

**HIGH — F3 `markDead` resume-token leak NEVER shipped despite VALIDATED.md claim** (claude HIGH-1, codex HIGH-3, opencode HIGH-2)
- `WorldWebSocketHandler.markDead:944-947` only removes `ATTR_ENTITY_ID`; never calls `resumeTokenRegistry.clearActive(entityId)` and never removes `ATTR_RESUME_TOKEN`.
- Downstream `cleanupBot`/`afterConnectionClosed` then read null entityId → skip clear path.
- ACTIVE entries have `expiresAtTick = Long.MAX_VALUE`, sweep never reaps → unbounded `tokenMap` growth on every death + every prey absorption.
- Fix: read entityId first, then `clearActive` + remove `ATTR_RESUME_TOKEN` before clearing `ATTR_ENTITY_ID`.

**HIGH — Mutagen survivor `randomBuff()` non-deterministic RNG consumption** (claude HIGH-2, gemini HIGH-3, codex MEDIUM)
- `EnvironmentEngine.tickBuffsAndInfections:723` iterates `infections.entrySet()` (CHM) → non-deterministic order.
- `processPendingGrants` → `randomBuff()` consumes shared `rng.nextInt()` in that order; non-commutative under RNG.
- Pass-2 dismissal cited DoT commutativity but missed the RNG-backed buff path.
- Breaks D-06 byte-exact replay determinism; ripples into all subsequent env-rng draws (lightning, gossip, toxin).
- Fix: sort snapshot by `entityId` before draining grants (or sort `infections.entrySet()` by key).

**HIGH — F1 composite formation still queues spurious DeathNotice** (codex HIGH-1, opencode HIGH-1)
- `SimulationEngine.updateBotRegistryForFormation:881-889` still uses `botRegistry.unregisterByEntity` + `register` instead of existing `botRegistry.remapEntity`.
- `unregisterByEntity` queues `DeathNotice` → Order(50) sends terminal `v|D` → `markDead` clears `ATTR_ENTITY_ID` of session that became a CompositeMember.
- Sessions get told they died on remap; bot respawn FSM fires; CompositeMember becomes orphan zombie. Violates WS:entity 1:1.
- Fix: use `botRegistry.remapEntity(sessionId, newMemberId, pos)` + keep `entityLifecycleListener.onEntityRemapped`. Add test asserting `drainDeaths()` empty after composite formation.

### Divergent Views

**HIGH — F2 `'B'` event code in `PerceptionCodec`** (codex flags HIGH; claude/gemini/opencode silent)
- Codex: `PerceptionCodec.validateEventCode` and `eventHasMagnitude` still reject `'B'` despite server emitting `v|B`; prey clients can't decode absorbed terminal frame.
- Other reviewers did not surface this. Worth verifying directly — if codex is right, this is a HIGH alongside F1/F3 as another claimed-shipped fix that didn't land.

**HIGH — `markStalled` tick-thread deadlock vs A1 completeness** (claude HIGH-3 vs gemini "no issues")
- Claude: A1 close-then-interrupt fixed `detachSession(WebSocketSession)` but `markStalled:728` still calls `detachSession(String)` overload which can't break drain VT mid-`sendMessage` → tick thread blocks on `synchronized(session)` for full Jetty WS write timeout.
- Gemini explicitly cleared A1 as complete with no regressions.
- Disagreement is about whether the String overload path is reachable from `markStalled` under stuck-VT conditions. Test gap acknowledged: no integration test drives `markStalled` with a stuck sender.

**HIGH — Concurrent registration races (LiveEntityRegistry / WS-thread vs tick thread)** (claude MED-1/MED-2, codex HIGH-4; gemini/opencode silent)
- Claude: position-keyed dedup needed; `LiveEntityRegistry.register` only keys on entityId, two concurrent registrations can land at same position → double-processing.
- Codex: structural mutations on WS thread between LiveEntityRegistry/WorldGrid mutations and BotRegistry binding; tick can run mid-window. Recommends serializing via tick-queue or shared lock.
- Opencode explicitly cleared cross-wave invalidation. Severity disputed (claude MEDIUM, codex HIGH); both agree real fix is atomic check-and-place against grid, deferred to P20.

**HIGH — `ActionResolver.drainActions` race drops frames** (gemini HIGH-1; others silent)
- Gemini: `pendingActions.remove(k, v)` returns false when WS thread overwrites mid-drain → bot excluded from current tick.
- No other reviewer surfaced this; worth verifying remove-by-key semantics in current source.

**HIGH — Buff/infection registry leak on disconnect** (gemini HIGH-2; others silent)
- Gemini: `cleanupBot`/`cleanupByEntityId` don't unregister from `BuffRegistry` or `DeathCleanupHooks`; only `DeathFinalizer` clears, never invoked for graceful disconnects.
- Distinct from HIGH-1 (resume tokens) — separate registries, separate leak. Unbounded under high-turnover load (P20/P22 harness).

**MEDIUM — Out-of-band frames bypass `FrameEmitListener`** (gemini MED-4; others silent)
- Gemini: `sendOutOfBand` bypasses test capture → 408 STALLED frames invisible to GoldenTrace.

**MEDIUM — `ToxinPathGenerator` uses own `new Random()`, not event seed** (codex MEDIUM; others silent)
- Codex: fixed `events.seed` doesn't make toxin paths deterministic; not reset by `resetForTest()`.

**MEDIUM — `CompositeEnergyDistributor` CHM iteration order + RNG** (codex MEDIUM; others silent)
- Codex: same root cause as F4 in a different bean; multiple composites diverge under fixed seed.

**MEDIUM — GoldenTrace doesn't exercise ActionResolver** (opencode MED-3; others silent)
- Coverage gap rather than a bug — digest gate produces zero coverage of M/E/R/composite-attack action paths.

**MEDIUM — `TickBroadcaster` at Order(50) contradicts CLAUDE.md** (opencode MED-5; others silent)
- Doc/code drift: CLAUDE.md describes split PerceptionBroadcaster@50 + TickBroadcaster@100 but code has merged single bean at @50. P20 split has more blast radius than planning assumed.

**LOW** — Detach-timeout has no metric (claude); A1 test doesn't prove VT exited (codex); `entitySnapshot()` called 4× in `processInteractions` (opencode). All single-reviewer; no consensus.

**Recommended priority before P20:** F1 (composite remap), F2 (PerceptionCodec `'B'`), F3 (resume-token leak), F4 (RNG sort) — three of four are claimed-shipped fixes that didn't land. Verify in source first.
