# Phase 22.1 multi-review backlog (PR #3, 2026-06-11)

Items deferred from the cross-AI review convergence loop (claude/codex/opencode/gemini, 5 rounds)
over PR #3 — the `EligibleCellIndex` VT-pinning fix and the `BotClient` reconnect/teardown
client-leak fix. All are real but sub-HIGH: latent hardening, additive test coverage, or the
deliberately-untouched second half of backlog 999.6. The HIGH/BLOCKER findings (reconnect-after-
disconnect leak, TOCTOU narrowing, publish-before-start revive, startup-path upgrade-failure leak,
leakProbe slow-tag drop) were all fixed in PR #3.

## TD-22.1-A — `EligibleCellIndex.add()/remove()` future lock-order inversion

**Surfaced:** opencode HIGH → downgraded on verification (multi-review round 1, 2026-06-11).

**Finding:** `add()` and `remove()` are public and take only the index lock. If a future tick
handler ever calls them while holding the grid **write** lock, the order becomes grid-write→index
and can deadlock against `notifyChanged`'s index→grid-read under the non-fair `ReentrantReadWriteLock`.

**Why deferred:** Verified **zero production callers** today — only `notifyChanged` (×30) and
`sample` (×3) are used in `src/main`. Not a live bug; the class javadoc already states the
index→grid-read invariant. Pure latent hardening.

**Fix sketch:** Make `add`/`remove` package-private, or assert the grid write lock is not held on
entry. Touches public API + test seams, so confirm no test depends on the visibility first.

---

## TD-22.1-B — `OutboundSender.@PreDestroy` slow-client half left open

**Surfaced:** claude L2 + opencode LOW-2 (multi-review round 1, 2026-06-11).

**Finding:** `@PreDestroy` uses the non-close-aware `detachSession(String)` and joins drain VTs
sequentially at up to 100 ms each (O(N×100ms)). A VT mid-`sendMessage` (blocked on the
`synchronized(session)` monitor / Jetty write) burns the full join and emits a "Sender VT … did
not exit" WARN — the 12 such warnings seen in the `forkEvery=0` full-suite run.

**Why deferred:** This is the **other half of backlog 999.6** — the `synchronized(session)`
drain-VT block that PR #3 intentionally does not touch (PR #3 fixed the VT-carrier-pinning and
client-leak halves). It is the reason `forkEvery=1` is retained (see TD-22-E); removing the
band-aid is gated on closing this. Not a regression.

**Fix sketch:** Have `@PreDestroy` use the close-aware `detachSession(WebSocketSession, CloseStatus)`
overload where it holds the session (via the registries), and/or detach in parallel rather than a
sequential join loop.

---

## TD-22.1-C — `BotClient.disconnect()` truly-synchronous stop

**Surfaced:** codex HIGH (severity) / opencode MEDIUM-1 (multi-review round 1, 2026-06-11).

**Finding:** `disconnect()` documents "callers expect the pools gone on return", but a racing Jetty
`onClose` can win the `clientStopped` CAS and move `c.stop()` onto `stopClientAsync`'s commonPool
task — so `disconnect()` can return before the pools are actually released. PR #3 softened the
comment to admit this; the client is still released (no leak), only the timing contract is loose.

**Why deferred:** Leak-free as-is; the only observable impact is a leak-probe false positive if a
caller checks thread counts immediately after `BotFleet.shutdown()`. A hard guarantee is extra
coordination for a narrow, test-only window.

**Fix sketch:** Have `stopClientAsync()` return a `CompletableFuture<Void>`; when `disconnect()`
loses the CAS, join that future (with a bounded timeout) before returning.

---

## TD-22.1-D — Deeper pool-release assertion in `BotClientTerminalCloseStopsClientTest`

**Surfaced:** claude M1 + codex MEDIUM + opencode MEDIUM-2 (multi-review round 1, 2026-06-11).

**Finding:** The client-stop tests assert `isClientStopped()` / `hasLiveClient()`, which flip when
the CAS wins / the field is nulled — before the async `c.stop()` has actually torn down the
scheduler/selector/qtp threads. They prove the stop path fires and no client is retained, not that
the platform threads died.

**Why deferred:** The four tests added in PR #3 already pin every fix path (failed reconnect,
reconnect-after-shutdown, per-instance re-arm, startup-path upgrade-failure — the last verified to
fail when its catch is removed). A thread-disappearance assertion is additive and flaky-prone.

**Fix sketch:** After the stop is observed, snapshot platform-thread names matching
`HttpClient@…-scheduler` (lean on `LeakCensusListener.normalise`) before/after and assert the
bot's pool threads are gone, with a bounded wait.

---

## TD-22.1-E — `BotClient.connect()` lazy-init non-atomic for concurrent connect

**Surfaced:** claude LOW (new observation, multi-review round 2, 2026-06-11).

**Finding:** `if (c == null) { … fresh.start(); this.client = fresh; }` is not atomic. Two
*concurrent* `connect()` calls could both observe null and both start a client — the loser's
started client would leak. The PR #3 teardown fixes (shutdown re-checks, per-instance CAS re-arm,
start-before-publish, catch-all release) close the disconnect-vs-connect races but do not make the
*non-shutdown* concurrent-create atomic.

**Why deferred:** Unreachable today — the only callers are the initial `BotFleet` launch-VT connect
(completes before any session exists) and `reconnect()` (scheduled once per `onClose`, strictly
sequential). Bites only if a second concurrent connect trigger is ever added.

**Fix sketch:** Gate the create on an `AtomicBoolean`/`ReentrantLock` — NOT `synchronized`, since
`client.stop()` blocks and would pin the VT carrier.
