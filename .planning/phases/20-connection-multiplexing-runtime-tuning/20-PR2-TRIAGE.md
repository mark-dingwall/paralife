# PR #2 — Triage (line-by-line, against actual code)

Branch `feat/diagnostics-instrumentation`. Multi-review `20-PR2-REVIEW-inline.md`
(4/4 reviewers: claude, gemini, codex, opencode; synthesizer claude). Pre-flight
seed findings P-1..P-5 reconciled against review output below.

Verdict legend: **FIX** (applied this PR) · **DOC** (documented in code) ·
**BACKLOG** (STATE.md Deferred Items) · **PUSHBACK** (declined, with reason).

## Severity-ordered dispositions

### H1 — `birthTick` leaks at every non-death `unregister` — **FIX** (commit `da82d00`)
- **Seed:** P-1 (composite-only). **Review escalated to BLOCKING, 4/4**, and broadened the surface.
- **Confirmed against code:** `recordBirth` is on the single `register` chokepoint (`LiveEntityRegistry.java:130`); `recordDeath` (the only reaper) sat on just two leaves — `DeathFinalizer.finalizeParticleDeath:121`, `finalizeBondedPairDeath:145`. Every other `unregister` dropped the id with no reap: bond formation (`SimulationEngine` ~720-722, unregisters predator/prey ids), composite formation (~821-822), `revertToBondedPair`/`dissolveToParticles` (direct unregisters), `cleanupCompositeMemberCellViaFinalizer:1193`, and WS disconnect/stall (`WorldWebSocketHandler:592,831,913`). All verified by trace.
- **Fix:** added `DeathDiagnostics.forget(id)` (silent, no log/counter) called from `LiveEntityRegistry.unregister` after successful removal. One death/exit chokepoint mirrors the one birth chokepoint. `recordDeath` still runs first on true deaths (logs+counts+removes) → `forget` no-ops there. Confirmed `lethalHint`/`preHitEnergy` never leaked (set+consumed same tick); reaped here too for symmetry.

### M2 — overcrowding shadows starvation — **FIX** (commit `4a965ea`)
- **Seed:** P-4 (was "verify ordering"). **Review found a real misattribution (HIGH, 3/4).**
- **Confirmed:** decay (P2) can drop a particle to `energy==0` with no hint; overcrowding (P2.5, `SimulationEngine:1090/1094`) then sees `0 - penalty <= 0` true and `putIfAbsent` stamps OVERCROWDING before the P3 reap. This biases the headline ~78%-starvation figure — the metric the tool exists to produce. Reviewers ranked this the top correctness fix.
- **Fix:** `&& energy() > 0` guard on all four hint sites (2 overcrowding = critical; 2 combat = defensive symmetry — combat runs P1 pre-decay so energy>0 already holds).

### M1 — composite-member deaths invisible to the census — **FIX** (commit `5c74950`)
- **Seed:** P-1's counter side. **Review MEDIUM, 4/4.**
- **Confirmed:** composite-member deaths route `finalizeCompositeMemberDeath` → `handleMemberDeath` → `cleanupCompositeMemberCellViaFinalizer`, none of which called `recordDeath` → `paralife.diag.deaths` + `DEATH-TRACE` omitted every composite death. Verified `cleanupCompositeMemberCellViaFinalizer` is **death-only** (callers `:1217`, `:1224`, `:1395`; transitions `revertToBondedPair`/`dissolveToParticles` unregister directly, **not** via cleanup) — so recording there counts true deaths without counting transitions.
- **Fix:** `recordDeath(cm.id(), cm.type().name())` at the top of `cleanupCompositeMemberCellViaFinalizer` (before its unregister) + `hintLethal(cm.id(), envCauseAt(x,y), 0)` on the CompositeMember branch in `processEnvDeaths` so env-killed members don't default STARVATION.
- **Divergence resolved (review Divergent View #1):** claude/gemini/codex wanted silent `forget` on transitions; opencode wanted `recordDeath("COMPOSITE")` on cleanup. Both adopted at their correct loci — `forget` for transitions (via unregister), `recordDeath` for true deaths (via the death-only cleanup). Census stays honest; leak closed everywhere.

### M3 — `envCauseAt` / splash-COMBAT best-effort misattribution — **DOC + BACKLOG** (commit `0640b34`; TD-PR2-A)
- **Seed:** P-3 (LIGHTNING default). **Review HIGH/MEDIUM, 4/4**, three modes: DoT-off-cell→LIGHTNING; lightning-on-toxin-cell→TOXIN/MUTAGEN; splash→COMBAT.
- **Disposition: PUSHBACK on the in-PR rework, per plan.** These are intentional best-effort heuristics for a diagnostic; they only shuffle attribution **within the env bucket** and do not move the headline starvation share (the metric the tool reports). The precise fix is invasive (thread `Cause` through `SplashDelta`/delta types and tag at each env damage site + `UNKNOWN` fallback) and is not warranted for an MVP diagnostic gated until the GUI. **Documented** the known modes/limits at both code sites; **backlogged** the rework as TD-PR2-A. Reassess if the deferred Population Viability work needs env-bucket precision.
- Note: did **not** change the LIGHTNING default to UNKNOWN (review Divergent View #2) — that alters documented intentional behaviour; deferred with the rest of the rework.

### L1 — `histogram()` + `causeCounts` dead/duplicate — **DOC + BACKLOG** (TD-PR2-B)
- **Seed:** P-2. **Confirmed:** zero production callers (`grep` — only unrelated Micrometer-latency-histogram matches in tests). `causeCounts` duplicates the tagged `paralife.diag.deaths` counter.
- **Disposition:** plan says defer removal-or-wiring if low. Marked `histogram()` as an intentional unwired future-summary hook in javadoc; backlogged the dedup/removal as TD-PR2-B.

### L2 — lifespan = grid-id lifetime, not lineage — **DOC** (commit `da82d00`)
- Trivial. Documented in `recordDeath` javadoc: each identity transition re-registers a fresh id, so a predecessor's lifespan is reaped by `forget` and not summed into the successor.

### L3 — `preHitEnergy=0` for env hints reads like a measurement — **BACKLOG** (TD-PR2-D)
- Trivial; deferred.

### opencode-only / divergent
- **Counter rebuild per death (opencode MEDIUM):** flag-on path only; pre-create/cache counters if death volume proves measurable. **BACKLOG** (TD-PR2-E).
- **`Cause.UNKNOWN` unreachable (opencode):** tied to M3 rework; resolved with TD-PR2-A.
- **combat-killed composite members default STARVATION:** minor residual census gap (members aren't hinted in `applyDeltaToOccupant`). **BACKLOG** (TD-PR2-C).

## Probe answers (review consensus, confirmed)
- **(a) thread-safety — PASS (4/4).** `recordBirth` on WS+tick threads inside `synchronized register`; `hintLethal`/`recordDeath` tick-only; CHM + `putIfAbsent`; disjoint keys; strict happens-before (on-grid before death). No lost/torn writes.
- **(d) flag-off inertness — PASS (4/4).** `@ConditionalOnProperty(havingValue="true")`, no `matchIfMissing`; all four consumers `@Autowired(required=false)` setters, null-guarded; ctor injection only inside the conditional bean. No `@Lazy` remnant on any DeathDiagnostics injection (the prior `@Lazy`+`required=false` NPE bug is gone). Verified independently.

## Pre-flight seed reconciliation
| Seed | Mapped to | Disposition |
|------|-----------|-------------|
| P-1 composite leak | H1 (broadened) + M1 | FIX |
| P-2 `histogram()` dead | L1 | DOC + BACKLOG |
| P-3 LIGHTNING default | M3 | PUSHBACK/DOC + BACKLOG |
| P-4 COMBAT first-wins / ordering | M2 (real bug found) + M3 splash | FIX (M2) + DOC/BACKLOG (splash) |
| P-5 stale TEMPORARY comments | Phase A | FIX (commit `b0a97d2`) |

**No unjustified pushback:** the only declined item (M3 rework) is a code-cited
intentional heuristic with documented limits and a backlog entry, consistent
with the plan's anticipated pushback stance.
