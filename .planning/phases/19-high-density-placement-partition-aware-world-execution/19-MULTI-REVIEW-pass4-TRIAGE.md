# Phase 19/19.5 Multi-Review Pass-4 — Merged Triage & Plan

**Synthesis date:** 2026-05-04
**Sources:** `19-MULTI-REVIEW-pass4-ref.md`, `19-MULTI-REVIEW-pass4-inl.md` (4 reviewers × 2 modes = 8 reports)

## Headline

**Three fixes the prompt listed as "shipped" never landed in source.** F1 (composite formation DeathNotice), F2 (`'B'` codec), F3 (`markDead` resume-token leak). All three confirmed against the live tree. This is the strongest signal of pass-4: the consensus-validated VALIDATED.md projection drifted from the actual source for at least three items.

Beyond that — two new HIGHs (RNG determinism, `markStalled` deadlock), several MEDs, and a stack of LOWs/perf/coverage notes.

## Verification matrix (vs source @ HEAD `d976975`)

| ID | Finding | Reviewers | Source check | Verdict |
|----|---------|-----------|--------------|---------|
| F1 | Composite formation `unregisterByEntity`+`register` queues spurious `DeathNotice` | codex (×2), opencode (×2) | `SimulationEngine.java:888` confirmed; `BotRegistry.unregisterByEntity:113` always queues notice | **REAL — unshipped** |
| F2 | `PerceptionCodec.validateEventCode` rejects `'B'` | claude-inl, codex (×2), opencode-inl | `PerceptionCodec.java:685` `case` list missing `'B'` | **REAL — unshipped** |
| F3 | `markDead` doesn't `clearActive` resume token | claude-ref, codex (×2), opencode-ref | `WorldWebSocketHandler.java:944-946` only removes `ATTR_ENTITY_ID`; downstream `cleanupBot` reads null | **REAL — unshipped** |
| F4 | `randomBuff()` consumes RNG in CHM iteration order | claude (×2), gemini (×2), codex (×2), opencode (×2) | `EnvironmentEngine.tickBuffsAndInfections` iterates `infections.entrySet()` from CHM; `processPendingGrants` drains in that order calling `randomBuff()` | **REAL — pass-2 dismissal incorrect** |
| MS | `markStalled` deadlock via `detachSession(String)` join-timeout | claude-ref, gemini-inl | `WorldWebSocketHandler.java:728` uses String overload; `OutboundSender.java:137-144` self-doc says interrupt can't unblock Jetty write; `sendOutOfBand:926` then blocks on `synchronized(session)` | **REAL** |
| AM | `AdmissionMetrics.bucketTagsByEntityId` not migrated on remap | claude-inl, codex-inl | `WorldWebSocketHandler.onEntityRemapped:829-842` updates ATTR + resume tokens, no metrics call | **REAL** |
| BL | `cleanupBot` doesn't clear `BuffRegistry` / infections / FLEEING on disconnect | gemini-ref | `cleanupBot:855-866` clears LiveEntityRegistry only; env-side state owned by `DeathFinalizer` only | **REAL** |
| FL | FLEEING not transferred at composite formation/dissolve to particle | codex-inl | Bond formation `transferFleeing` ✓ at `SimulationEngine.java:763`; revert ✓ at 1272; **bp→cm formation MISSING**; cm→particle dissolve MISSING | **REAL — minor (auto-expires)** |
| TX | `ToxinPathGenerator` uses unseeded `new Random()`, not reset by `resetForTest()` | codex-ref | `EnvironmentEngine.java:267,298` constructs `new ToxinPathGenerator()` (no-arg → unseeded `new Random()`) | **REAL** |
| CE | `CompositeEnergyDistributor` iterates `compositeRegistry.getAll()` consuming RNG | codex-ref | `CompositeEnergyDistributor.java:97` iterates getAll(); shuffle at :104 consumes `compositeRng` | **REAL** |
| CR | Concurrent registration races (LiveEntityRegistry dup-position; ws-thread vs tick) | claude-ref (×2), codex-ref | Confirmed — single-threaded reg invariant per D-06; multi-thread reg explicitly out of scope | **REAL — deferred to P20 per existing comment** |
| GT | `GoldenTraceEquivalenceTest` doesn't exercise ActionResolver verbs | opencode (×2), claude-ref-implicit | `GoldenTraceEquivalenceTest.driveScenario` queues no actions | **REAL coverage gap** |
| OD | `@Order` doc drift: CLAUDE.md says PerceptionBroadcaster@50 + TickBroadcaster@100; code has only TickBroadcaster@50 | opencode (×2) | `TickBroadcaster.java:189 @Order(50)`; no `PerceptionBroadcaster` class exists | **REAL doc drift** |
| OS | OOB frames bypass `FrameEmitListener` (golden-trace can't see 408) | gemini-ref | `sendOutOfBand:922-932` calls `sendMessage` directly | **REAL — minor coverage gap** |
| LM | `LiveEntityRegistryInvariantTest` lacks movement/reproduction coverage | gemini-inl | Test only covers AT-REST/POST-DEATH/POST-BOND/POST-COMPOSITE | **REAL coverage gap** |
| EL | A1 test doesn't assert sender VT exited | gemini-inl, codex-inl | `OutboundSenderTest.detachSessionWithSessionRefUnblocksInFlightSend` asserts elapsed only | **REAL test gap** |
| L1 | Detach-timeout has no metric, only log line | claude-ref | `OutboundSender.detachSession(String):154-156` warn-only | **REAL observability nit** |
| EN | `EnvironmentEngine.processEnvDeaths` still uses full grid scan | opencode-inl | Confirmed | **REAL perf vector — defer** |
| ES | `entitySnapshot()` called 4× redundantly in `processInteractions` | opencode-ref | Confirmed | **REAL perf nit — defer** |
| NS | Nutrient spawning per-spawn `notifyChanged` | opencode-inl | Confirmed | **REAL perf nit — defer** |
| FE | `FrameEmitListener` inside `synchronized(session)` is footgun | claude-inl | Doc-only; current single in-tree user is fast | **REAL doc nit** |
| FD | Frame drop on detach contract not documented | opencode-inl | Doc-only | **DOC** |
| **DR** | `ActionResolver.drainActions` race drops frames | gemini-ref HIGH#1 | Pass-2 M-A explicitly chose this semantic; failed `remove(k,v)` leaves new value for next tick — **deferred, not lost**. Comment at `ActionResolver.java:397-399` confirms intent | **DECLINE — false positive** |

## Decline rationale (one item)

**DR (gemini-ref HIGH#1)**: Pass-2 M-A explicitly chose `remove(k,v)` semantics. If a WS thread overwrites mid-drain, the *new* value remains in the map and is drained next tick. Bot's current-tick action defers by one tick — it is **not lost**. This is the documented behaviour at `ActionResolver.java:397-399`. Gemini misread the contract. No change.

## Triage by sequencing

### Block P20 (must-ship)

**Three bug-fixes that fraudulently appeared "shipped" — close before P20 starts compounding them.**

1. **F2 `'B'` codec** — 1-line fix + round-trip test. Smallest blast radius, highest current breakage (every prey-of-bond session silently fails to respawn).
2. **F1 composite formation remap** — replace `unregisterByEntity`+`register` with existing `remapEntity`. Add test asserting `drainDeaths()` empty after composite formation. Same shape as already-working bond/dissolve sites.
3. **F3 `markDead` resume-token clear** — read entityId before remove; `clearActive`+remove `ATTR_RESUME_TOKEN`. Add test asserting tokenMap empty after death-without-reconnect.

**Two new HIGHs.**

4. **F4 RNG determinism** — sort `pendingGrants` by entityId in `processPendingGrants` (or sort `infections.entrySet()` by key in `tickBuffsAndInfections`). Same pattern fixes CE (`CompositeEnergyDistributor` snapshot+sort by compositeId) and TX (`ToxinPathGenerator` reset-aware seeded ctor).
5. **MS `markStalled` deadlock** — switch markStalled to `detachSession(WebSocketSession)` (close-aware). Accept that 408 may not arrive; the `close(SERVICE_RESTARTED)` is itself the reconnect signal. Document this trade-off explicitly. Add integration test driving stuck-VT scenario through markStalled, asserting tick thread doesn't block beyond a small bound.

### Should-ship before P20 lands churn

6. **AM `AdmissionMetrics.remapBucketTags`** — add helper, call from `onEntityRemapped`. Slow-leak fix; matters more under high formation churn that P20 will produce.
7. **BL disconnect-cleanup leaks** — invoke `buffRegistry.unregisterEntity`, `hooks.clearInfectionOnDeath`, and `environmentEngine.fleeing.remove` from `cleanupBot` and `cleanupByEntityId`. Gemini's instinct on harness-load OOM under P20/P22 is correct.
8. **FL FLEEING transfer at composite boundaries** — call `transferFleeing(bp.id(), cm.id())` for each member at composite formation; same at cm→particle dissolve. Auto-expiring so not catastrophic, but cheap and complete.

### Test coverage to add alongside fixes

9. **GT GoldenTrace ActionResolver scripts** — add `GoldenTraceWithActionsTest` driving deterministic M/E/R verbs. Pin a separate baseline.
10. **LM movement/reproduction invariant tests** — extend `LiveEntityRegistryInvariantTest`.
11. **EL A1 VT-exit assertion** — package-private `senderThreads` accessor + `t.join(2000)` + `isAlive() == false`.
12. **Codec convention gate** — round-trip test per `Event.java` code; iterate the switch list.

### Defer (capture in tech-debt log, not P20-blocking)

- **CR concurrent registration races** — atomic check-and-place against grid is the proper fix. Tied to P20's connection-multiplexing surface anyway.
- **OD `@Order` doc drift** — fix CLAUDE.md (one paragraph). The code is fine; only doc is wrong.
- **EN/ES/NS perf** — defer to Phase 21 perf pass. Document the assumption.
- **OS / FE / FD / L1** — observability nits, doc nits.

## Push-back items (where I disagree with reviewers)

- **DR (gemini-ref HIGH#1, drainActions race)** — Decline. Pass-2 M-A explicitly chose this semantic; reviewer misread.
- **CR severity (codex-ref HIGH-4)** — Codex called concurrent registration race HIGH; I keep it MEDIUM and **defer**. D-06 explicitly tightened to single-threaded reg. The system is self-correcting (overcrowding penalty) and there's an existing comment flagging deferral. Codex's "P20 will compound" critique is valid but P20 is the right place to fix it (atomic check-and-place needs the multiplex protocol design first).
- **EL A1-VT-exit (gemini-inl HIGH#3)** — Gemini called this HIGH; I keep it LOW. Test gap, not a code bug. The omitted `join()` is intentional per the A1 design — close-then-interrupt unblocks Jetty's write, the IOException path causes the loop to exit on the next iteration. The test should still assert this, but the production behaviour is correct.

## Recommended plan structure

If we open this as a Phase 19.6 follow-up rather than rolling into P20:

```
Plan 01 — Unshipped fix verification & landing
  Task 01a: F2 codec 'B' + round-trip test
  Task 01b: F1 composite-formation remap + drainDeaths-empty test
  Task 01c: F3 markDead clearActive + token-leak test

Plan 02 — Determinism hardening
  Task 02a: F4 sort pendingGrants in processPendingGrants
  Task 02b: CE sort compositeRegistry snapshot in CompositeEnergyDistributor
  Task 02c: TX seed ToxinPathGenerator from EnvironmentEngine.spawnRng + reset hook
  Task 02d: Determinism test — same-tick multi-cure, multi-composite, multi-toxin

Plan 03 — Backpressure correctness
  Task 03a: MS switch markStalled to detachSession(WebSocketSession)
  Task 03b: Document close-then-best-effort-OOB contract
  Task 03c: Integration test — stuck VT under markStalled, tick-thread bound assertion
  Task 03d: EL VT-exit assertion in OutboundSenderTest
  Task 03e: L1 detach-timeout metric

Plan 04 — Lifecycle leaks
  Task 04a: AM AdmissionMetrics.remapBucketTags + onEntityRemapped hook
  Task 04b: BL cleanupBot/cleanupByEntityId env-state cleanup
  Task 04c: FL FLEEING transfer at composite formation + cm→particle dissolve
  Task 04d: Invariant test — bucket-tags, buff/infection/fleeing maps empty after disconnect

Plan 05 — Test coverage
  Task 05a: GT GoldenTraceWithActionsTest (M/E/R/V scripts)
  Task 05b: LM LiveEntityRegistryInvariantTest movement/reproduction cases
  Task 05c: Codec convention gate (round-trip per Event code)

Plan 06 — Doc + nits
  Task 06a: OD CLAUDE.md @Order accuracy
  Task 06b: FE FrameEmitListener contract Javadoc
  Task 06c: FD drainLoop close-time frame-drop comment
  Task 06d: ES processInteractions snapshot hoist (1 call, 3 reuses)
```

Plans 01–04 should ship before P20 starts. Plan 05 hardens regression coverage in parallel. Plan 06 is doc/nit cleanup and can land anytime.

## Confidence notes

- F1/F2/F3/F4/MS/AM/BL/FL: verified directly against source.
- TX/CE: spot-verified via grep — codex's chain holds.
- DR: verified against intent comment + behaviour analysis.
- CR: verified — claude/codex agree on root cause, disagreement is severity.
- All other findings accepted on reviewer evidence (read but not re-verified).
