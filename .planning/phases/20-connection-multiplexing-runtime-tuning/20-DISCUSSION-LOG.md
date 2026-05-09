# Phase 20: Connection Multiplexing & Runtime Tuning - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-09
**Phase:** 20-connection-multiplexing-runtime-tuning
**Areas discussed:** Context reuse strategy, Codec perf gate (TD-22-B), Golden-trace flakiness (TD-19.5-A), Profile run isolation, plus 4 new gray areas (G-01..G-04) surfaced by audit
**Prior artifact:** `20-CONTEXT-superseded-pre-P19.1.md` (drafted 2026-05-02, before P19.1 + P22 landed)

---

## Context Reuse Strategy

User's instruction: "we need to launch a thorough investigation into which questions are
still relevant, and whether the answers still make sense or whether the project has changed
enough that the question/answer needs to be reevaluated. Also need to check and confirm for
gaps in these questions to see if any new questions should be investigated."

**Action taken:** Dispatched general-purpose audit agent. Inputs: superseded CONTEXT.md,
STATE.md, ROADMAP.md, REQUIREMENTS.md, CLAUDE.md, P19.1 plan SUMMARY files, P22 SUMMARY,
key source files (`OutboundSender`, `WorldWebSocketHandler`, `AdmissionMetrics`,
`AdmissionConfig`, `PerceptionCodec`), `build.gradle.kts`, P19 CONTEXT.

**Audit verdict:** 12/16 decisions STILL VALID, 4 NEEDS UPDATE (D-06, D-10, D-11, D-12),
0 OBSOLETE, 0 GAP. Plus 2 file-path corrections in `code_context` (OutboundSender +
PerceptionCodec packages). Plus 4 NEW gray areas surfaced (G-01..G-04). Recommended verdict:
amend in place — fresh CONTEXT would re-derive ~80% identically.

**Mechanical fixes applied to new CONTEXT.md (no user discussion needed):**

| Decision | Issue | Fix |
|---|---|---|
| D-06 | No mention of P22's `forkEvery=1` + 5-min JUnit timeout | Added: profile measurement runs via standalone `loadHarnessJar`, P22 in-test constraints don't apply |
| D-10 | Wrong path `com.paralife.engine.PerceptionCodec` | Corrected to `com.paralife.codec.PerceptionCodec`; sibling `Base64Codec` noted |
| D-11 | Single gate cited; TD-19.5-A flakiness not flagged | Three-gate stack (P19 D-10 + P19.1 D-11 + P19.1 D-12); flake caveat documented; in-suite signal only |
| D-12 | "166+ tests" stale; no mention of 4 P22 `@Disabled` | Drop stale figure (current = 136 test files); explicit "P20 MUST NOT re-enable TD-22-A..D" |
| code_context | `OutboundSender` + `PerceptionCodec` paths wrong | Fixed to `com.paralife.admission.OutboundSender` + `com.paralife.codec.PerceptionCodec` |

---

## G-01: P22.1 Invariant Diff Ownership

| Option | Description | Selected |
|--------|-------------|----------|
| A: P22.1 owns its diff (Recommended) | P20 nothing extra. Cleanest separation; P22.1 inserted exactly because P20/P21 changes need revalidation. | ✓ |
| B: P20 SC adds invariant gate | Add to P20 success criteria: 'no regression in 22-INVARIANTS.md invariants.' Catches drift in P20 itself, not at P22.1 time. | |
| C: P20 ships invariant-check script | P20 writes small script P22.1 reuses. More upfront cost; tighter feedback loop. | |

**User's choice:** "I need your guidance choosing a path that gets us (safely) to MVP more
directly, time is tight on this project" → recommended Option A on MVP-direct grounds.
**Notes:** Cheapest path; P22.1 already exists as the safety net. Drift caught at most one
phase later, not six months later. Acceptable risk for MVP. Captured as **D-17**.

---

## G-02: L1 Detach-Timeout Counter Usage

| Option | Description | Selected |
|--------|-------------|----------|
| A: Add to D-13 headline numbers (Recommended) | Track alongside `paralife.tick.health.work-time-ms` in before/after deltas in 20-RUNTIME.md. Read-only signal, no new knob. | ✓ |
| B: Promote to tunable knob | Add `paralife.runtime.app.detach-timeout-ms` knob. More surface, possibly premature. | |
| C: Reference only in profile-findings | Mention in 20-RUNTIME.md narrative; don't elevate to headline. | |

**User's choice:** Option A.
**Notes:** Captured as **D-18**. Tracks the new metric without expanding tunable surface
prematurely.

---

## G-03: Profile Baseline Anchor

| Option | Description | Selected |
|--------|-------------|----------|
| A: Anchor to specific commit SHA (Recommended) | Pin baseline to e.g. `c22e487` (P19.1 close). Reproducible; reviewers re-run same baseline. Cite in 20-RUNTIME.md filename + profile artifact. | ✓ |
| B: Anchor to first commit on P20 branch | Whatever HEAD is when P20 starts. Less precise but pragmatic; baseline drifts each rebase. | |
| C: Soft anchor (text only) | 'baseline = post-P19.1 HEAD' in prose, no SHA. Cheapest; least reproducible. | |

**User's choice:** Required ELI5 explanation first ("can you ELI5?"). After explainer-mode
walkthrough of "before vs after" tuning comparison and how P19.1 D-07 (markStalled
close-aware detach) shifted the tick-thread blocking profile, user locked Option A.
Additional instruction: "add a post-MVP backlog item to consider re-running baseline so
that JFR analysis is a proper apples to apples comparison."
**Notes:** Captured as **D-19** with SHA `c22e487` (P19.1 close commit). Backlog item
recorded in `<deferred>` section: re-capture baseline against tuned-system HEAD post-MVP
to keep comparisons relevant once P21 + P22.1 land.

---

## G-04: `paralife.runtime.app.*` Namespace Handling

| Option | Description | Selected |
|--------|-------------|----------|
| A: Layer alongside, don't move (Recommended) | Leave `paralife.admission.backpressure.outbound-queue-size` in place; new keys land under `paralife.runtime.app.*`. Cheapest; CLAUDE.md / 17-ADMISSION.md untouched. | ✓ |
| B: Deprecate-and-alias | Add `paralife.runtime.app.outbound.queue-size` reading old key as fallback. Cleaner long-term namespace; small migration burden in docs. | |
| C: Hard-rename | Move outbound-queue-size into `paralife.runtime.app.*`, update all cross-refs. Cleanest; highest ripple cost. | |

**User's choice:** "Layer alongside, add rename task to backlog."
**Notes:** Captured as **D-20**. Backlog item recorded in `<deferred>` section (Phase 999.x:
namespace consolidation, deprecate-and-alias migration of outbound-queue-size).

---

## Claude's Discretion

Carried forward unchanged from superseded CONTEXT (D-07 footer):
- Concrete `paralife.runtime.jetty.*` and `paralife.runtime.app.*` field names and defaults
- Profile artifact size bounds
- Exact LoadHarness ramp / duration / seed combinations for per-tier profile runs
- Whether `paralife.runtime.app.*` is a single record or split
- Choice of GC for each tier (ZGC vs G1)
- Format of profile-finding citations in `20-RUNTIME.md`

---

## Deferred Ideas (added by this discussion)

- **Phase 999.x: `paralife.runtime.app.*` namespace consolidation** — fold
  `paralife.admission.backpressure.outbound-queue-size` (and siblings) under
  `paralife.runtime.app.outbound.*` via deprecate-and-alias. Per D-20.
- **Phase 999.x: P20 baseline JFR re-run for apples-to-apples** — re-capture baseline
  against latest tuned HEAD post-M4 close so future tuning decisions compare against
  current reality. Per D-19 follow-up instruction.

Existing deferred items from superseded CONTEXT carried forward unchanged.
