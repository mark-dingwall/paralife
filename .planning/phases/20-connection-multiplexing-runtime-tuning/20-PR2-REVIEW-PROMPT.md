# Multi-review — PR #2 `feat/diagnostics-instrumentation`

## What this is (motivation)

Flag-gated **death-cause + lifespan trace** instrumentation. A new bean,
`DeathDiagnostics` (package `com.paralife.diagnostics`), plus five call-site
hooks in the engine. It answers a single question at scale: when entities die,
*why*? — starvation (food deficit / energy decay), combat (RPS), overcrowding,
or environment (toxin / mutagen / lightning).

**Origin:** Phase 20 viability investigation (2026-05-25). It surfaced the
food-deficit "death-treadmill" finding (≈78% starvation at production defaults).
It is **retained, not throwaway** — kept as standing instrumentation to support
the deferred Population Viability & Energy Balance work.

**Gating:** `@ConditionalOnProperty("paralife.diagnostics.death-trace.enabled"=true)`.
When the flag is off the bean is **absent**, and every call site is guarded by
`if (deathDiagnostics != null)` — so the production default path is zero-cost.
Data exits via a per-death `DEATH-TRACE` log line and a `paralife.diag.deaths`
Micrometer counter (tagged `cause`,`type`).

## Change surface (what is in scope)

- **`20-PR2-REVIEW-DIFF.md`** — the authoritative change surface
  (`git diff main...feat/diagnostics-instrumentation`, 5 files / +175/-2).
- **`DeathDiagnostics.java`** — supplied in full; it is a brand-new file, all of
  it is new code.
- The four engine files (`SimulationEngine`, `EnvironmentEngine`,
  `DeathFinalizer`, `LiveEntityRegistry`) are **large pre-existing** files
  supplied for context only. **Only the diff hunks in those files are in scope.**
  Do not review pre-existing code outside the hunks.

## Probe explicitly

(a) **Thread-safety.** `recordBirth` runs on **two** threads: the WebSocket
    inbound thread (`handleRegister` → `LiveEntityRegistry.register`) **and** the
    tick thread (reproduction / budding also go through `register`).
    `hintLethal` and `recordDeath` are **tick-thread-only**. The state is four
    `ConcurrentHashMap`s; `hintLethal` uses `putIfAbsent` (first-claim-wins per
    tick). Are the maps + `putIfAbsent` correct under that threading? Is anything
    lost or racy (e.g. a birth recorded after a death, counter/ordering issues)?

(b) **Map-entry lifecycle (most important).** Every entity that gets a
    `recordBirth` entry must eventually be reaped by a `recordDeath` (which
    `remove`s from `birthTick` / `lethalHint` / `preHitEnergy`). Trace every
    registration path and every death path. **Known suspect:** composite-member
    deaths go through `SimulationEngine.handleMemberDeath` /
    `DeathFinalizer.finalizeCompositeMemberDeath`, which do **not** call
    `recordDeath`. If composite members are `register`ed, their map entries leak
    (unbounded growth while the flag is ON). Confirm whether composite members
    register, and whether this is a real leak.

(c) **Cause attribution correctness / ordering.**
    - `envCauseAt` returns TOXIN if `toxinGrid>0`, MUTAGEN if `mutagenGrid>0`,
      else **defaults to LIGHTNING** (lightning leaves no persistent grid). Is
      the default acceptable as a best-effort heuristic, or can it misattribute?
    - COMBAT hint is set in `applyDeltaToOccupant` via `putIfAbsent` (first wins).
      Sim runs `@Order(10)`, env `@Order(14)`. Can a COMBAT hint shadow a real
      env cause, or vice-versa, given that ordering?

(d) **Flag-off inertness.** Confirm there is **no boot-time coupling** when the
    flag is off — the bean is absent, setters are `@Autowired(required=false)`,
    constructor injection only inside the conditional bean. A prior
    `@Lazy`+`required=false` arrangement caused an NPE/boot bug; it has been
    removed — **confirm no remnant remains** that could break startup or the
    three golden-trace / invariant gates.

(e) **`histogram()` deadness.** `histogram()` is `public` but has no caller
    (data exits via log + counter). Is it dead API to remove, or a cheap
    future-summary hook to keep?

## Out of scope

- **Balance / death-treadmill tuning** — deferred until a visual GUI exists.
  Do not propose energy/metabolic/env retuning.
- The **external profiling scripts/artifacts** under
  `.planning/phases/20-.../profiles` — not part of this PR.
- Pre-existing engine code outside the diff hunks.

## Output

Inline review. Per finding: severity (blocking / high / medium / low / trivial),
file:line, the mechanism, and a concrete recommendation. Flag any
misattribution with a concrete reproducing scenario (that is the bar for
changing the best-effort heuristics in (c)).
