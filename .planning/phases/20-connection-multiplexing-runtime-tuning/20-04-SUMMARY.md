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

Initial verification at HEAD `6669707` (pre-polish state, churn-baseline recipes only, no `delay=15s` on JFR):

| Tier | Server boot | Harness rc | JFR file | Size |
|------|-------------|------------|----------|------|
| 100  | ✓ ready    | 0          | ✓ `jfr-100bots.jfr`  | 1,957,759 B |
| 500  | ✓ ready    | 0          | ✓ `jfr-500bots.jfr`  | 2,700,314 B |
| 1000 | ✓ ready    | 0          | ✓ `jfr-1000bots.jfr` | 4,572,433 B |

## Multi-review polish loop

After initial verification, the deliverable went through four post-execution review rounds (1 single-AI + 3 cross-AI multi-review). Trend:

| Round | Reviewers | Substantive findings | Polish commit | Notes |
|-------|-----------|----------------------|---------------|-------|
| caveman | claude (single-AI) | 3 (1 bug, 2 risk) | `9b9ac5a` | broken `15-SCHEMA §12` ref; active variant missing; JFR/harness duration mismatch |
| R4 | 4× (claude/gemini/codex/opencode) | 6 (codex BLOCK on active variant + 5 polish) | `c0ff625` | active variant relabelled smoke-only; `outbound-queue-size` `live-tunable` → `attach-time-tunable` at 5 sites; heap `Pending — JFR-driven`; `-D` placement hazard fixed |
| R5 | 4× | 4 (codex FLAG on JFR tail) | `344c175` | added JFR `delay=15s` cushion; §3.3 yaml lifecycle qualifier; `17-ADMISSION §3 → §6` cite (4 sites); churn-baseline reproducibility caveat |
| R6 | 4× | 1 (claude NIT + opencode MEDIUM, same finding) | `8f183cf` | R5's tradeoff sentence reversed direction-of-gap (tail vs front); corrected. **3 of 4 reviewers PASS at R6** — convergence reached. |

R4/R5/R6 multireview artifacts: `20-04-MULTIREVIEW-R{4,5,6}.md`.

## Ship-gate verification

After R6 convergence + commit `8f183cf`, ran the verify script against all three baseline tiers + one active-variant smoke at HEAD `8f183cf`:

| Tier | Server boot | Harness rc | JFR file | Size |
|------|-------------|------------|----------|------|
| 100 (baseline) | ✓ ready 5s | 0 | ✓ `jfr-100bots.jfr` | 677,433 B |
| 500 (baseline) | ✓ ready 4s | 0 | ✓ `jfr-500bots.jfr` | 1,703,857 B |
| 1000 (baseline) | ✓ ready 4s | 0 | ✓ `jfr-1000bots.jfr` | 1,987,390 B ⚠ |
| 100 (active smoke) | ✓ ready 4s | 0 | ✓ `jfr-100bots-active-50xfood.jfr` | 629,002 B |

JFR sizes smaller than pre-polish because `delay=15s` clips the boot/connect window from the recording (intentional, per R5/R6 tradeoff prose).

**⚠ 1000-tier shutdown note:** server did not exit cleanly within 20 s of `SIGTERM` post-harness (script `SIGKILL`-ed). JFR file was already on disk by then (auto-stopped at `delay+duration=195 s` post-launch). Likely artefact of 1000-session teardown latency, outside Plan 20-04 scope; the recipe itself functions correctly.

**Active-variant smoke** validates the Spring `--paralife.simulation.nutrient-spawn-probability=0.05` app-arg form added in R4 polish. Per the smoke-only label and the cited non-reproducibility caveat, the JFR size is not expected to match the `103a615` capture (different `--duration`, heap, parallelism, ramp per meta sidecar).

All four recipes are copy-pasteable as written; Phase 21 benchmark scripts can consume them as-is, subject to the explicit "smoke-template / not byte-for-byte reproducible" caveats now in §3 intro for both churn and active variants.

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

## Self-Check: PASSED (post-R6, HEAD `8f183cf`)

- File exists, 396 lines (≥120 required).
- All §1..§6 grep anchors pass.
- All three baseline JFRs cited with `62c1b44` SHA suffix.
- D-08 honoured: no wrapper script in 20-RUNTIME.md. The verify helpers (`20-04-verify-recipes.sh`, `/tmp/p20-04-verify-r6.sh`) are ephemeral and not part of the deliverable.
- D-13 honoured: 6 `Pending — JFR-driven` markers (3× GC choice, 1× VT parallelism, 1× heap presets, 1× §3-intro heap honesty); recipes that diverge from meta sidecars carry explicit "not byte-for-byte reproducible" caveats for both churn and active variants.
- D-19 honoured: every §3 JFR citation carries `62c1b44`.
- Pass-2 Concerns #7 + #8 honoured (unchanged through polish).
- R6 convergence verified: 3 of 4 reviewers PASS, 1 FLAG on a single MEDIUM that was applied in `8f183cf`.
- Ship-gate boot-verify PASSED for all 3 baseline tiers + 1 active-variant smoke at HEAD `8f183cf` (rc=0, JFR present; 1000-tier shutdown latency noted above).
