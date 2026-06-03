---
phase: 20-connection-multiplexing-runtime-tuning
plan: 04
status: complete
completed: 2026-06-04
requirements: [SCALE-09]
key_files:
  created:
    - .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md
---

# Plan 20-04 Summary

## What was built

`20-RUNTIME.md` — the canonical Phase 20 spec doc (D-14). 322 lines, six top-level sections:

- **§1** WS:entity 1:1 architectural principle (D-01/D-02 locked rationale + D-03 revisit-multiplex forward note).
- **§2** Four-layer tuning surface (D-07) — JVM/Jetty/app/codec. §2.1 binds 8 `JettyRuntimeConfig` knobs; §2.2 tags all 4 `AppRuntimeConfig` fields `[reserved — no effect in Phase 20]` (Pass-2 Concern #7); §2.3 covers codec impl.
- **§3** Per-Scale-Tier Recipes — **3 copy-pasteable recipes (100/500/1000 bots)** for Phase 21 benchmark scripts.
- **§4** Profile Findings — placeholders; Plan 5/6 populates §4.2 headline numbers + §4.3 narrative + §4.4 codec opts.
- **§5** Forward Notes — admin-UI live-tune (M5), automated config search, D-03 trigger, Phase 999.4 namespace consolidation, Phase 999.6 ReentrantLock conversion.
- **§6** Profile Index — 62c1b44 baseline series + 103a615 active-50xfood series, both indexed.

## §3 recipe inventory

| Tier | Sub-section | Baseline JFR cited | Harness count/duration |
|------|-------------|--------------------|------------------------|
| 100  | §3.1 | `profiles/jfr-100bots-baseline-62c1b44.jfr` | 100 / 60s |
| 500  | §3.2 | `profiles/jfr-500bots-baseline-62c1b44.jfr` | 500 / 90s |
| 1000 | §3.3 | `profiles/jfr-1000bots-baseline-62c1b44.jfr` | 1000 / 180s |

All three cite the post-F6 re-anchor SHA `62c1b44` (D-19). §3 intro carries four guard-rail blocks: D-08 (no wrapper script), F1 admission-cap=1500 benchmark-time override rationale, active-vs-churn profile selection (62c1b44 vs 103a615), Pass-2 Concern #8 (queue-watermark-pct deliberately omitted).

## Human verification (Task 4.2 checkpoint)

Recipes executed verbatim against HEAD (commit `6669707`) on Linux 6.6 WSL2 via `20-04-verify-recipes.sh` driver:

| Tier | Server boot | Harness rc | JFR file | Size |
|------|-------------|------------|----------|------|
| 100  | ✓ ready    | 0          | ✓ `jfr-100bots.jfr`  | 1,957,759 B |
| 500  | ✓ ready    | 0          | ✓ `jfr-500bots.jfr`  | 2,700,314 B |
| 1000 | ✓ ready    | 0          | ✓ `jfr-1000bots.jfr` | 4,572,433 B |

All three recipes are copy-pasteable as written; Phase 21 benchmark scripts can consume them as-is.

## Pending markers for Plan 5 / Plan 6

Plan 5 (codec opts + tuned JFR) and Plan 6 (final write-up) must replace these markers in `20-RUNTIME.md`:

| Line | Marker | Replaced by |
|------|--------|-------------|
| 154 | §3.1 GC choice rationale `Pending — JFR-driven; G1 conservative default` | Plan 5/6 — measured-justified GC for 100-tier |
| 195 | §3.2 GC choice rationale `Pending — JFR-driven; ZGC candidate if GC pause >2%` | Plan 5/6 — chosen GC + JFR finding |
| 242 | §3.3 GC choice rationale `Pending — JFR-driven; G1 baseline; ZGC switch IFF …` | Plan 5/6 — chosen GC + JFR pause stats |
| 244 | §3.3 VT scheduler parallelism rationale `Pending — JFR-driven; parallelism=8 placeholder` | Plan 5/6 — measured choice or flag removed |
| 270 | §4.2 headline numbers row | Plan 1c actuator sidecars (baseline) + Plan 5 tuned sidecar |

§4.3 (per-tier narrative) and §4.4 (codec hot-path opts) are placeholders Plan 6 / Plan 5 populate end-to-end.

## D-13 honesty

No untraceable recommendations. Every recipe knob is either:
- Cited to the 62c1b44 baseline JFR (heap sizing, parallelism placeholder), or
- Explicitly marked `Pending — JFR-driven` (GC choice, VT parallelism rationale).

## Pass-2 disposition confirmation

- **Concern #7** — `frame-size-budget-bytes` tagged `[reserved — no effect in Phase 20]` in §2.2 RUNTIME table at line 70. All four `AppRuntimeConfig` fields carry the `[reserved]` tag.
- **Concern #8** — Zero `queue-watermark-pct:` yaml key lines in §3.2/§3.3 fenced override blocks. Commented prose mentioning the field is intentional (explains the deliberate omission).

## Self-Check: PASSED

- File exists, 322 lines (≥120 required).
- All §1..§6 grep anchors pass.
- All three baseline JFRs cited with `62c1b44` SHA suffix.
- D-08 honoured: no wrapper script in 20-RUNTIME.md. The verify helper (`20-04-verify-recipes.sh`) is ephemeral and not part of the deliverable.
- D-13 honoured: 5 `Pending — JFR-driven` markers.
- D-19 honoured: every §3 JFR citation carries `62c1b44`.
- Pass-2 Concerns #7 + #8 honoured.
- Human boot-verify PASSED for all three tiers (rc=0, JFR present).
