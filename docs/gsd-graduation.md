# GSD Graduation — decision record

Dated rationale (archival, non-normative) behind graduating Paralife's workflow *off* GSD onto the
lean "June loop" now encoded in [`/CLAUDE.md`](../CLAUDE.md) §How-we-work. Distilled from a
read-only investigation (workflow A/B/C bake-off + a 16-primitive learning diff + a four-part
OpenSpec deep-dive), 2026-06. Kept for the **why** and the **what-we-deliberately-left-out** — not a
process to follow. Where this disagrees with CLAUDE.md or the code, they win.

## Thesis

OpenSpec's core stance is ~80% the June loop already — it disagrees with **GSD**, not with us, and
names GSD's failure mode (phase-locking, fixed-depth ceremony) as the field-wide anti-pattern. So the
investigation produced **external validation for graduating, not a new tool to install**. Nothing in
the adopt set was machinery: every keeper is a habit, a markdown section, or a one-line forcing
function — and they are now folded into CLAUDE.md (the spec-doc skeleton, EARS as the mechanism-spec
notation + emergence firewall, evidence-bound "done", scope-diff, merge-back close-out, the rigor
dial). The whole CLI lifecycle, four-artifact folder, schema/DAG engine, and SQLite ledger were
rejected — they re-grow exactly the ceremony we shed.

**Standing prerequisite, still load-bearing:** gate- and scenario-shaped habits (EARS-RED,
scope-diff, merge-back) are only as honest as the test signal. De-flaking the golden-trace gate
(close the `OutboundSender.awaitAllSessionQueuesDrained` VT race, TD-19.5-A) remains the real
unlock before leaning on RED/GREEN as truth. And the emergent layer stays out of any default-suite
assertion regardless.

## The reject fence (do NOT re-import)

The investigation's most durable output: the ceremony deliberately left on the table. Re-growing any
of these is a regression to GSD.

| Rejected | Why |
|----------|-----|
| OpenSpec CLI + propose→apply→archive orchestrator | Re-creates GSD phase-lock as `status --json` round-trips; a solo dev + frontier model holds the change in context. |
| Four-artifact change folder (proposal+specs+design+tasks) | Parallel-change isolation is worthless for one-change-in-flight; PR-per-slice is the reviewable unit. |
| Archive merge engine (header-match replay, conflict resolution) | The part that needs the CLI — do the delta fold by hand at merge-back. |
| SQLite / `state.db` ledger + per-task CONTEXT/REPAIR/STATUS files | Literally the defunct `gsd.db`; `STATE.md` + auto-`MEMORY.md` + git suffice. |
| Fixed N-phase pipelines (QRSPI 6-node, flokay 7-phase) | The fixed-depth-per-change ceremony the whole SDD field names as the anti-pattern; mine individual beats à la carte. |
| BMAD 12-persona / Party Mode infra | A solo dev driving one model needs no simulated roles. |
| Schema/DAG engine + per-change YAML overrides | Adopt the "process is prunable owned data" *framing*, not the engine. |
| Spec-Kit / Kiro full upfront-clarity kit | The "sledgehammer to crack a nut"; only EARS and the Constitution idea survived the mining. |
| Dual source-of-execution-truth | Kept as a *principle to enforce*: never two docs over the same contract — keep `.planning/` frozen, CLAUDE.md singular. |
| Scenario/EARS/completeness-gate on the **emergent** layer | The cardinal sin — population stability / niche formation must never be pinned by a default-suite test. |

## Open questions (decisions deferred)

| # | Question | Status (2026-06-30) |
|---|----------|---------------------|
| 1 | De-flake golden-trace before fully trusting the gate-shaped habits (verify-signal trust). | **Open** — the live prerequisite; fix the VT race first, rest can land alongside. |
| 2 | CLAUDE.md ruthless-brevity vs the value of the provenance/migration narrative — split injected lean core from a non-injected provenance archive? | **Resolved** — lean injected CLAUDE.md + on-demand `docs/`; provenance lives in frozen `.planning/`. |
| 3 | Does the deterministic core churn slowly enough that one capability-keyed living spec pays for hand-maintained deltas, vs change-scoped-then-superseded? | **Piloting** — the EARS layer now extends to `ADMISSION.md` §0 (Rollout #2) alongside `SCHEMA.md`; payoff still pending **one real admission-touching merge-back** (the HARNESS-rollout trigger in `BACKLOG.md`) to confirm the cadence pays before extending further. **Standing tax:** §0 anchors are uncompiled doc strings — every admission test rename/move silently rots an anchor with nothing to catch it. That recurring re-sync cost *is* what the HARNESS gate exists to price. |
| 4 | A standing dated inconsistencies ledger vs an ad-hoc slice-close coherence sweep — is the upkeep worth it? | **Open** — leaning sweep; contract-tests may already cover drift. |
| 5 | Does Paralife need any milestone planning object above the PR, or is the roadmap enough? | **Open** — lean: roadmap = planning, PR = execution, nothing between. |
| 6 | Auto session-log via a session-end hook to keep `STATE.md` current — worth it, or machinery? | **Open** — edges toward machinery; verify cost for a solo loop first. |
| 7 | Which capability spec to graduate to EARS + merge-back first? | **Resolved** — `SCHEMA.md` (cleanest exemplar); EARS pilot landed `7d44a80`. |
| 8 | SHALL/MUST vs SHOULD/MAY keyword strength — only worth it if MUST⇒gated / SHOULD⇒never-gated is actually held. | **Open** — decoration without the convention; confirm the discipline holds before adopting. |
