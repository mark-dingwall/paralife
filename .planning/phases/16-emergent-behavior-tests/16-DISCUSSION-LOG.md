# Phase 16: Emergent Behavior Tests - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in `16-CONTEXT.md` — this log preserves the alternatives considered.

**Date:** 2026-04-21
**Phase:** 16-emergent-behavior-tests
**Areas discussed:** Test scope layout, Emergent pattern choice & docs form, Stable ecosystem criteria, Load regression baseline, Black-box UAT inclusion, Protocol surface in emergence tests

---

## 1. Test Scope Layout

### Q1a — Layout

| Option | Description | Selected |
|--------|-------------|----------|
| 1 — One fat test | Single `@SpringBootTest` covers R15/R16/R18 in one seeded 500+ tick run. Fastest wall-clock, single-failure hides which R broke | |
| 2 — One test per R | Separate test per requirement. Clean diagnosis on fail, 3× Spring startup cost | |
| 3 — Hybrid | Split R15 short/deterministic from R16+R18 long-run | ✓ |

**User's choice:** Hybrid.

### Q1b — Long-run runtime cap

| Option | Description | Selected |
|--------|-------------|----------|
| a — 30s | | |
| b — 60s | | |
| c — 120s | | |
| d — Other | CI can run for minutes in background; local terminal should stay fast | ✓ |

**User's choice:** CI-tolerant (minutes acceptable on GitHub free-tier runner), local ≤90s target. Minimise tick interval with safety buffer.

---

## 2. Emergent Pattern Choice & Docs Form

### Q2a — Pattern(s) tracked

| Option | Description | Selected |
|--------|-------------|----------|
| 1 — Toxic-zone prey refuge | | |
| 2 — Composite clustering | | |
| 3 — Pack-hunt via attack-cure | | |
| 4 — RPS spiral waves | | |
| 5 — Seasonal population oscillation | | |
| Other — Combo | Bonded pairs + composites + STARVING-pack + RPS boom-bust + flee-from-buffed, using trigger-watcher rolling-avg for behavioural signals | ✓ |

**User's choice:** Five-signal combo. Configured bonding/composite rates may be bumped to force observable emergence in test-scale worlds. Trigger-watcher pattern for signals that require candidate appearance.

### Q2b — Docs form

| Option | Description | Selected |
|--------|-------------|----------|
| a — Markdown only | | ✓ |
| b — Markdown + JSON fixture | Added as D-06b after Claude flagged as cheap insurance | ✓ |
| c — Markdown + JSON + charts | | |
| d — Markdown + JSON + visualiser snapshots | Blocked by M5 | |

**User's choice:** Markdown (a). Accepted D-06b (JSON fixture, rollover N=5, gitignored) after suggestion.

---

## 3. Stable Ecosystem Criteria

### Q3a — Criteria definition

| Option | Description | Selected |
|--------|-------------|----------|
| 1 — No-extinction only | | |
| 2 — Population floor per type | | |
| 3 — Oscillation amplitude floor | | |
| 4 — Combined | All three asserted separately | ✓ |

**User's choice:** Combined (4).

### Q3b — Duration

| Option | Description | Selected |
|--------|-------------|----------|
| 500 ticks | Matches v1.0 baseline | |
| 1000 ticks | | ✓ |
| Longer | | |

**User's choice:** 1000 ticks.

### Q3c — Seed strategy

| Option | Description | Selected |
|--------|-------------|----------|
| a — Byte-stable single seed | | |
| b — Component-seeded, statistical | | ✓ |
| Hybrid — (a) for R15, (b) for R16 | Initial recommendation | |
| c — Random + N repeats + quorum | | |

**User's choice:** Component-seeded + statistical for both R15 and R16. Byte-stable dropped after Claude noted the seeded-statistical variant satisfies the spirit of R15 at far lower maintenance cost — user agreed.

**Notes:** User raised a future post-MVP idea — overnight Bayesian / param-sweep tuning for emergent-behaviour config. Recorded as deferred.

---

## 4. Load Regression Baseline (R18)

### Q4 — Original options

| Option | Description | Selected |
|--------|-------------|----------|
| 1 — Re-run Phase 10 LoadTest semantics | | |
| 2 — Freeze Phase 15 baseline with tolerance | | |
| 3 — Dual baseline | | |
| Other — Reframe to capacity-headroom | User observed: features will grow, wire volume will grow legitimately; the contract is "don't lose runway," not "don't grow." | ✓ |

**User's choice:** Reframed R18 from "no regression from v1 baseline" (wire-parity) to **capacity-headroom stability** — feature-agnostic ratios. Test renamed `EmergenceStabilityLoadTest`. Assertion set chosen: tick drift <10%, mean tick work ≤50% of budget, p99 ≤90% of budget, 0 dropouts, <20% heap growth post-warmup, 0 ERROR logs, active-session gauge stable.

**Notes:** Composite formation forced via config so R18 exercises composite load path (not particle-only).

---

## 5. Black-box UAT Inclusion

### Q5a/b — Original options

| Option | Description | Selected |
|--------|-------------|----------|
| 1 — JUnit only | | ✓ (final) |
| 2 — JUnit + full UAT for all Rs | | |
| 3 — Hybrid: JUnit gates + UAT evidence for R17 only | Initial recommendation | |

**User's exploration:** Initially agreed with hybrid (3). Asked for indefinite-duration operator UAT (Ctrl+C at operator discretion). Claude proposed Micrometer counters + log markers + optional HTML dashboard (D-14b).

User correctly pushed back: if UAT only confirms what JUnit asserts, it adds no value. Real UAT value = human watches the sim and makes a subjective call on "is it pleasing / interesting." That needs a visualiser.

Three follow-up options presented:
1. Defer subjective UAT to M5 (visualiser scope)
2. Build minimal observer in Phase 16 (scope creep into M5)
3. Split into Phase 16 + Phase 16.1 observer harness

**User's final choice:** Option 1 — defer subjective UAT to M5. Shipping timeline is close; keep Phase 16 tight.

**Kept:** Micrometer `paralife.emergence.*` counters (D-14), `EMERGENCE` log markers (D-15) — both cheap and pay forward to M5.
**Dropped:** `16-UAT.md`, HTML dashboard (D-14b), indefinite operator UAT script.

**User's proposal not pursued:** WebSocket devtools Chrome extension as operator UAT tool. Claude flagged as wrong tool — bots are JVM-to-JVM (invisible to browser extension) and zero-trust vision means a single session wouldn't reveal world-scale emergence even if browser-attached.

---

## 6. Protocol Surface in Emergence Tests

### Q6 — Test surface per requirement

| Option | Description | Selected |
|--------|-------------|----------|
| R15 engine-direct, R16/R17/R18 full-stack | Minimum surface per claim | ✓ |
| Full-stack for everything | Extra confidence, extra noise | |

**User's choice:** R15 engine-direct (pure sim-rules claim); R16/R17/R18 full-stack (claims about the running system).

**Notes:** User asked for a plainer explanation of goal before deciding. Answer: pick minimum test surface that makes each requirement falsifiable. Engine-direct keeps R15 clean + deterministic; full-stack required for R16/R17/R18 because their claims are about the running system including transport.

---

## Deployment question (asked during write-up)

User asked whether full-stack means local-JVM or real-world deployment (Railway server + Fly.io clients or vice versa).

Claude clarified: full-stack in Phase 16 = `@SpringBootTest(webEnvironment = RANDOM_PORT)` — single-JVM, real Jetty server + real `BotClient`s on localhost random port. Cross-host deployment is **M6 (Deployment)**, explicitly out of scope for v2.0 milestone.

User agreed — M6 owns real deployment. No change to Phase 16 decisions.

---

## Claude's Discretion

Items deferred to research/planner (recorded in CONTEXT.md `Claude's Discretion` subsection):
- Bonding/composite config values that reliably force observable formation in test-scale worlds
- Rolling-window W and radius R for trigger-watcher signals
- Per-component seed-derivation scheme (SplittableRandom.split vs hash-based)
- Oscillation-amplitude floor default (0.15 starting point)
- Heap measurement mechanism
- `EmergenceMetrics` bean package placement
- `@Tag("slow")` usage for long-run test in CI

## Deferred Ideas

- Subjective human-observer UAT / "pleasing to watch" eval → M5 visualiser phase
- Live operator HTML dashboard → M5
- Per-session WS inspector / browser devtools → M5 global observer endpoint
- Bayesian / param-sweep tuning for emergent-behaviour config → post-MVP
- Byte-stable fixture snapshots → rejected; revisit only for forensic replay need
- Prometheus-scrape emergence counters → M5
- Chart / plot rendering of per-tick series → M5 or dedicated writeup phase
