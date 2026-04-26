# Paralife GSD Doc Reconciliation — Findings

**Audit date:** 2026-04-20
**Scope:** Every markdown file under `.planning/` and `.gsd/` cross-checked against live code at commit `264bfd6` (Phase 15 close).
**Method:** 5 batches × 4 parallel `Explore` sub-agents, each producing a per-file verdict with code evidence. Raw agent reports live under `/tmp/paralife-reconciliation/batch{1..5}-agent{1..4}.md` (ephemeral; this doc is the durable synthesis).
**Output constraint:** no source docs were edited. This file + `project_summary.md` at the repo root are the only net-new artifacts.

---

## Executive Summary

The `.planning/` and `.gsd/` corpus is broadly **healthy and trustworthy**, but three categories of drift are worth naming:

1. **`.planning/codebase/*.md` is badly stale** — dated 2026-04-12, predates Phases 13/14/15. Misses entire `codec/` and `metrics/` packages, claims `Messages.java` still exists, under-counts the engine package by 23 classes. This is the only set of docs that would actively **mislead** a new reader about current architecture.
2. **`REQUIREMENTS.md` and `v2.0-MILESTONE-AUDIT.md` lag behind completion.** R01–R03 (Phase 11) and R20–R29 (Phase 15) are still flagged "Pending"; the v2.0 audit is a 2026-04-13 snapshot that pre-dates Phase 15 execution. Both read as "in-flight" even though Phases 11–15 have all shipped.
3. **Phase 04/07/08 summaries carry protocol claims that Phase 15 overturned.** Messages.java has been deleted, PerceptionBroadcaster renamed/moved into `websocket/TickBroadcaster`, `@Order(100)` demoted to `@Order(50)`. These older summaries remain factually correct as *historical records* but would confuse a today-reader without a `HISTORICAL` header.

Everything else — PROJECT.md, ROADMAP.md, STATE.md, MILESTONES.md, all Phase 11–15 plans/summaries, GSD2 legacy artifacts — is current or benignly historical. No load-bearing contradictions exist between STATE.md/ROADMAP.md and live code.

**Most authoritative doc for "where we are now":** `.planning/STATE.md` (timestamp 2026-04-20T18:45:00Z, Phase 15 complete, next = Phase 16 planning).

**Source-of-truth precedence when docs disagree:** STATE.md > ROADMAP.md > phase summaries > `.planning/codebase/*.md` > v2.0-MILESTONE-AUDIT.md > REQUIREMENTS.md > `.gsd/` legacy.

---

## Reconciliation Scorecard

| Bucket | Files | Verdict distribution |
|---|---:|---|
| Top-level `.planning/` | 8 | 6 CURRENT, 1 PARTIAL (REQUIREMENTS), 1 STALE (v2.0-MILESTONE-AUDIT) |
| `.planning/codebase/` | 4 | **All 4 STALE or CONTRADICTS-CODE** |
| `.planning/milestones/` | 2 | 2 HISTORICAL-OK (v1.0) |
| `.gsd/` legacy | 6 | 6 HISTORICAL-OK |
| Phases 01–05 (GSD2-era stubs) | 7 | 6 HISTORICAL-OK, 1 NEEDS-HEADER (04) |
| Phases 06–07 | 11 | 9 CURRENT, 1 NEEDS-HEADER (07-01-SUMMARY), 2 HISTORICAL-OK |
| Phases 08–09 | 7 | 5 HISTORICAL-OK, 2 NEEDS-HEADER (08-01, 09-01 summaries) |
| Phase 10 | 3 | 2 HISTORICAL-OK, 1 NEEDS-HEADER (10-01-SUMMARY) |
| Phase 11 | 13 | 13 CURRENT (all Phase 11 decisions still in force) |
| Phase 12 | 19 | 19 CURRENT (1 note on 12-05 JSON wire-format superseded by Phase 15) |
| Phase 13 | 14 | 14 CURRENT |
| Phase 14 | 17 | 17 CURRENT (+ 3 LOW code-quality OPEN items: WR-01/02/03) |
| Phase 15 | 31 | 31 CURRENT |
| **Total** | **~142** | **Majority healthy; stale concentrated in 4 codebase docs + 2 status trackers + 4 historical summaries** |

---

## Cross-cutting Narrative — How We Got Here

Understanding the doc staleness requires understanding the project's design-evolution arc:

1. **M001 Foundation (Phases 01–05, GSD2-managed).** Spring Boot scaffold, `WorldGrid`, `TickEngine`, raw WebSocket handler, 100-bot integration test. Decisions D001–D005 in `.gsd/DECISIONS.md` all still in force: Java 21 virtual threads, raw WebSocketHandler (no STOMP), 2D toroidal grid, Gradle Kotlin DSL.
2. **M002 Living Simulation (Phases 06–10).** Sealed `Entity` hierarchy, RPS combat, JSON `Messages` protocol, `BotClient` with Jackson, `LoadTest` + `PopulationDynamicsTest`. Shipped 2026-04-12 as v1.0, score 14.5/15. Migrated from GSD2 → GSD1 mid-milestone (2026-04-11).
3. **v2.0 Combination & Emergence (Phases 11–16).** Six-phase arc stacking mechanics on the M002 base:
   - **Phase 11 (Bonding):** adds `BondedPair` entity, predator+prey bonding, 25% combat deflection.
   - **Phase 12 (Composites):** `CompositeMember` + `Role` enum, shared energy pool, LOCOMOTOR IRV voting, dissolution/panic-zone mechanics.
   - **Phase 13 (Metabolism):** per-type `MetabolicProfile`, starvation intensity, fertility patches, cosine-based seasons, hybrid vigor on bonded pairs.
   - **Phase 14 (Environment):** `EnvironmentEngine` at `@Order(14)`, toxin/mutagen/lightning/compost effects, shadow grids, status-bitmask projection, survivor buffs.
   - **Phase 15 (Protocol overhaul):** JSON → compact-text binary-adjacent `PerceptionCodec`, Tomcat → Jetty 12 with permessage-deflate fail-fast, `Messages.java` deleted, `PerceptionBroadcaster` merged into `TickBroadcaster`, zero-trust per-bot perception, Micrometer observability, Jetty-native stateless `BotClient`.
   - **Phase 16 (Emergent behaviour tests):** not started.

Every architectural description written before Phase 15 is now partially wrong about the wire layer. Every codebase intel doc written before Phases 13/14 is missing `codec/`, `metrics/`, and ~23 engine classes. Documentation lag is concentrated at that fault line.

---

## Per-Doc Reconciliation Table

Legend: `CURRENT` = still accurate · `HISTORICAL-OK` = frozen record, not misleading · `HISTORICAL-NEEDS-HEADER` = factually dated, would mislead a today-reader without a banner · `PARTIAL` = status fields out of date · `STALE` = whole doc predates major change · `CONTRADICTS-CODE` = actively wrong.

### Top-level `.planning/`

| Path | Verdict | Specific fix |
|---|---|---|
| `PROJECT.md` | CURRENT | Bump "Last updated" from 2026-04-13 → 2026-04-20. Optional: add `codec/`, `metrics/` to tech-stack bullet; mention Phase 15 protocol overhaul in narrative. |
| `REQUIREMENTS.md` | **PARTIAL** | Flip R01, R02, R03 (Phase 11) and R20–R29 (Phase 15) from `Pending` → `Complete`. Add a `last-updated:` frontmatter field. |
| `ROADMAP.md` | CURRENT | Small inconsistency: bottom progress table shows Phase 13 as "Planning complete" and Phase 14 as "Context complete" while the per-phase plan checkboxes above are all `[x]`. Align the table rows to `✅ Complete`. |
| `STATE.md` | CURRENT | Authoritative. No change. |
| `MILESTONES.md` | CURRENT | Tech-debt list still lists 5 v1.0 items as open — all 5 are now resolved or moot (see v1.0 audit section below). Worth updating or adding a "status as of Phase 15" column. |
| `config.json` | CURRENT | No change. |
| `v2.0-MILESTONE-AUDIT.md` | **STALE** (2026-04-13) | Marks R15–R19 / R20–R29 "unsatisfied" but Phase 15 (2026-04-20) completed these. Either rerun `/gsd-audit-milestone v2.0` post-phase-15, or prepend a `SUPERSEDED — see ROADMAP + STATE for current status` header. |
| `v2.0-TECH-DEBT.md` | PARTIAL | DF-E (synthetic wire-format strings) became moot when `Messages.java` was deleted in Phase 15-11; mark as RESOLVED. DF-B (MetabolismIntegrationTest flake) still valid. |

### `.planning/codebase/` — **all stale**

| Path | Verdict | Specific fix |
|---|---|---|
| `ARCHITECTURE.md` | **CONTRADICTS-CODE** | (a) `Messages.java` no longer exists — protocol is `codec/Frame.java` sealed with 5 subtypes (r/S/T/a/E). (b) `PerceptionBroadcaster.java` deleted; perception merged into `TickBroadcaster`. (c) 4-phase tick pipeline is wrong — current order is 10 → 14 → 15 → 20 → 25 → 50. (d) Entity hierarchy has 5 permits (Particle, Rock, Nutrient, BondedPair, CompositeMember), not 3. |
| `STRUCTURE.md` | **STALE** | (a) Missing packages `codec/` (13 classes) and `metrics/` (1 class). (b) Engine package listed as 9 classes, actual count 32. (c) WebSocket package missing `JettyDeflateCustomizer`, `WebSocketRouteAssertion`. (d) Test count listed ~19, actual 69 (569 `@Test` methods). |
| `STACK.md` | **PARTIAL** | (a) Missing `org.eclipse.jetty.websocket:jetty-websocket-jetty-client:12.0.18`. (b) Claims "no custom metrics" — Micrometer + `WebSocketMetrics` is live. (c) Config section enumerates 3 `paralife.*` sections; actual is 10+ (rock, bonding, composites, environment, seasons, fertility, buffs, types, etc.). |
| `INTEGRATIONS.md` | **CONTRADICTS-CODE** | (a) WebSocket message types section describes JSON `Messages.*` records — delete; replace with `Frame` codec schema pointing at `15-SCHEMA.md`. (b) "No custom metrics" is wrong — document `paralife.ws.active.sessions` (Gauge) + `paralife.ws.tick.frame.bytes` (DistributionSummary) exposed at `/actuator/metrics/*`. (c) `permessage-deflate` is mandatory (server filter rejects upgrades without it); origin policy description is out of date. |

**Recommended:** regenerate all four via `/gsd-map-codebase` against the current tree, then spot-check — faster than line-editing 1–2 hours of drift.

### `.planning/milestones/` (v1.0)

| Path | Verdict | Specific fix |
|---|---|---|
| `v1.0-ROADMAP.md` | HISTORICAL-OK | No change — accurate post-completion record. |
| `v1.0-MILESTONE-AUDIT.md` | HISTORICAL-OK | Tech-debt list is the source for root CLAUDE.md's 5 items. Per our verification, **all 5 are now resolved or moot** — consider adding a "Post-v1.0 status" addendum: |
|  |  | • `Cell.nutrientLevel` inert → RESOLVED by Phase 14 (EnvironmentEngine writes it; SimulationEngine reads as fertility multiplier). |
|  |  | • `PerceptionBroadcaster` UNKNOWN-for-dead workaround → MOOT; dead-entity handling reworked in Phases 10 and 15. |
|  |  | • `HeuristicBrain.predatorType` dead-branch ternary → RESOLVED by Phase 15-09 (unconditional `myType.predator()`, comment at `HeuristicBrain.java:28–30`). |
|  |  | • `BotClient` raw `JsonNode`/`LinkedHashMap` → RESOLVED by Phase 15-09 (Jackson removed; `PerceptionCodec` is sole wire I/O). |
|  |  | • `LoadTest` implicit `nutrient-consume-energy` → RESOLVED; `LoadTest.java:31` sets it explicitly. |

### `.gsd/` legacy archive

All 6 files HISTORICAL-OK. D001–D005 (WebSocket, virtual threads, toroidal grid, Gradle, raw handler) all still in force — replicated into `.planning/PROJECT.md`. Recommendation: add a 2-line `.gsd/README.md` header pointing future readers to `.planning/`. Zero migration gaps found.

### Phases 01–05 (M001 Foundation, GSD2-era)

All 7 files are 1-line CONTEXT stubs plus phase-01's PLAN+SUMMARY. All HISTORICAL-OK except:

| Path | Verdict | Specific fix |
|---|---|---|
| `04-websocket-connection-manager/04-CONTEXT.md` | HISTORICAL-NEEDS-HEADER | Phase 04 established the JSON `Messages` wire protocol; Phase 15 replaced it with binary `PerceptionCodec`. Add: *"Superseded protocol. See Phase 15 for the current wire format."* |

### Phases 06–07

| Path | Verdict | Specific fix |
|---|---|---|
| `06-*/*.md` (5 files) | CURRENT | Sealed `Entity` + immutable `Cell` + `ReentrantReadWriteLock` envelope all still authoritative. `Cell.nutrientLevel` was inert in v1.0 but is now actively written by Phase 14 — worth a footnote but not a blocker. |
| `07-CONTEXT.md`, `07-02-*`, `07-03-*` | HISTORICAL-OK | Foundation physics tests still pass. |
| `07-01-SUMMARY.md` | **HISTORICAL-NEEDS-HEADER** | Two concrete wrong claims: (1) "TickBroadcaster `@Order(100)`" — actual `@Order(50)` at `TickBroadcaster.java:139`. (2) "4-phase tick" — current pipeline is 6+ phases. Prepend a *"Superseded. See Phase 13–15 for evolved tick pipeline. @Order values updated."* banner. |

### Phases 08–09

| Path | Verdict | Specific fix |
|---|---|---|
| `08-CONTEXT.md`, `08-02-*`, `09-CONTEXT.md` | HISTORICAL-OK | `ActionResolver @Order(20)` and `Direction` enum still active and authoritative. |
| `08-01-SUMMARY.md` | **HISTORICAL-NEEDS-HEADER** | Describes JSON `Messages` + `PerceptionBroadcaster @Order(50)` — both deleted in Phase 15. Prepend historical banner pointing at Phase 15 (`PerceptionCodec`, `TickBroadcaster`). |
| `08-03-SUMMARY.md` | **HISTORICAL-NEEDS-HEADER** | Integration test patterns still valid, but JSON wire-format claim is dated. One-line header suffices. |
| `09-01-SUMMARY.md` | **HISTORICAL-NEEDS-HEADER** | Raw `JsonNode`/`LinkedHashMap` and dead-branch `predatorType` — both flagged as v1.0 tech debt, both resolved in Phase 15-09. Prepend: *"Tech debt #3 and #4 both resolved by Phase 15."* |

### Phase 10

| Path | Verdict | Specific fix |
|---|---|---|
| `10-CONTEXT.md`, `10-01-PLAN.md` | HISTORICAL-OK | No change. |
| `10-01-SUMMARY.md` | HISTORICAL-NEEDS-HEADER | Claim "160 tests total, all green" was true at v1.0 close; test count is now 569 `@Test` methods / 561 passing. Phase 15-11 added `EncodeDeflatePerformanceGateTest` at the same 100-bot scale. One-line banner. |

### Phases 11–14 (v2.0 pre-protocol)

**All plans, summaries, QA artifacts CURRENT** — 63 files. No rewrites needed.

Open items carried forward:

- **Phase 13 deferred items (v2.0-TECH-DEBT.md):** DF-A (BondedPair speculative cached fields) still present but unused. DF-B (`MetabolismIntegrationTest` ~50% flake under full-suite load — virtual-thread leakage) still open, known, documented. DF-C (hybrid vigor integer truncation) defensible. DF-F (nutrient regen/decay absent) in scope. DF-E (synthetic wire strings) **mootted by Phase 15 `Messages.java` deletion** — update `v2.0-TECH-DEBT.md`.
- **Phase 13 UAT gap:** `HeuristicBrain.REPRODUCE_THRESHOLD` is hardcoded at 70; SPORE `maxEnergy` default < 70 forced a test workaround. File as Phase 16+ tech debt.
- **Phase 14 code-review warnings (`14-REVIEW.md`):** all three still open, all LOW-priority hygiene:
  - **WR-01:** `BuffRegistry` orphan entries on `BondedPair` death — asymmetric cleanup vs infection map. Bounded by buff expiry; no correctness impact. Fix sketched in `14-REVIEW.md:84–106`.
  - **WR-02:** `revertToBondedPair` uses `cm.id()` for both primary and secondary slots — idempotent today but confusing to future readers.
  - **WR-03:** solo-particle multi-neighbour attack has no `break;` — intentional (splash stacking test depends on it) but the intent lives only in tribal knowledge; a single comment would fix it.

All three belong in a Phase 16 hygiene plan or as one-off cleanups.

### Phase 15 (protocol overhaul)

**All 31 files CURRENT.** Schema is locked (`15-SCHEMA.md`), patterns doc tracks the live tree (`15-PATTERNS.md`), all 11 plans + summaries shipped, deferred-items register resolved. `15-REVIEWS.md` is pre-execution (cross-AI review session) — historical by design.

Resolved review items: Vector-9 coord ambiguity (locked in SCHEMA pre-code), `Messages.java` strip sequencing (deleted as planned), Jetty/Spring dual registration (single Spring-only path confirmed by `JettyDeflateCustomizer.java:44–47`), bytes-saved metric (intentionally deferred per SCHEMA §13, Jetty 12 API limitation).

Deferred: `paralife.tick.drift.millis` metric (performance gate uses connection-survival proxy; follow-up plan), `MetabolismIntegrationTest` flake (Phase 16 hygiene).

---

## Code-vs-Doc Discrepancies (catalogued)

| Claim in doc | Actual code | Severity |
|---|---|---|
| `ARCHITECTURE.md`: "Messages sealed interface in `websocket/Messages.java`" | File deleted; replaced by `codec/Frame.java` | HIGH |
| `ARCHITECTURE.md`: "PerceptionBroadcaster @Order(50)" | Class deleted; merged into `websocket/TickBroadcaster @Order(50)` | HIGH |
| `ARCHITECTURE.md`: "4-phase tick pipeline: 10 → 20 → 50 → 100" | 6-phase: 10 (Sim) → 14 (Env) → 15 (Composite) → 20 (Action) → 25 (EnvPost) → 50 (Tick) | HIGH |
| `STRUCTURE.md`: "engine/ has 9 classes" | 32 classes | MEDIUM |
| `STRUCTURE.md`: no `codec/` or `metrics/` packages | 13 + 1 classes live | HIGH |
| `STACK.md`: no Micrometer, no Jetty client dep | Both present and load-bearing | MEDIUM |
| `INTEGRATIONS.md`: "origin policy *, no custom metrics" | Permessage-deflate enforced via servlet filter; 2 Micrometer meters exposed | MEDIUM |
| `07-01-SUMMARY.md`: "TickBroadcaster @Order(100)" | `@Order(50)` | MEDIUM |
| `08-01-SUMMARY.md`: "JSON Messages protocol, PerceptionBroadcaster" | Both replaced in Phase 15 | LOW (historical record; needs header) |
| `09-01-SUMMARY.md`: "BotClient uses JsonNode" | Jackson removed; `PerceptionCodec` only | LOW (historical) |
| `REQUIREMENTS.md`: R01–R03, R20–R29 marked `Pending` | All shipped per STATE.md/ROADMAP | MEDIUM |
| `v2.0-MILESTONE-AUDIT.md`: R15–R19 "unsatisfied" | Satisfied — Phase 15 complete | MEDIUM |

No HIGH-severity contradictions in load-bearing state docs (STATE.md, ROADMAP.md, PROJECT.md). All HIGH severity is concentrated in the 4 codebase intel docs.

---

## Open Gaps — Live in Code, Missing in Docs

1. **Six packages (not four).** The `com.paralife.*` tree has grown to `bot/`, `codec/`, `engine/`, `metrics/`, `websocket/`, `world/`. `PROJECT.md` and `.planning/codebase/*.md` still imply four.
2. **Observability surface.** Nowhere in the top-level docs is the Micrometer / Actuator story told. Meters, endpoints, and what to watch in production aren't captured.
3. **Perception authority tiers** (full / authority-lite / passive per composite role) are defined in `15-SCHEMA.md §7` but never surfaced in higher-level docs. This is one of the more interesting design patterns and worth a one-paragraph summary in `PROJECT.md` or the new `project_summary.md`.
4. **Performance gates.** Phase 10's load-test-centric framing ignores the new `EncodeDeflatePerformanceGateTest` (Phase 15-11) that measures tick-loop drift under compression.
5. **Test suite growth.** Docs reference 166 / 19 / 202 test counts at various pre-Phase-15 moments. Current count is 69 test files / 569 `@Test` methods / 561 passing. Worth a single canonical citation.
6. **Zero-trust principle.** Phase 15 formalises "server sends only what the bot can actually see, entity IDs never leak" — this is a distinctive design property not captured in any general-purpose doc.

---

## Recommended Edit Order

If the next step is a `/gsd-docs-update` pass that wants clean input, tackle these in order for maximum leverage per edit:

1. **Regenerate `.planning/codebase/*.md` entirely** (`ARCHITECTURE`, `STRUCTURE`, `STACK`, `INTEGRATIONS`). These are the only docs actively misleading new readers. A `/gsd-map-codebase` run against HEAD is cheaper than line-editing them.
2. **Flip status in `REQUIREMENTS.md`** — R01–R03 and R20–R29 → `Complete`. Add `last-updated` frontmatter.
3. **Rerun `/gsd-audit-milestone v2.0`** — the 2026-04-13 audit is the single biggest outdated summary document. Or add a `SUPERSEDED` banner if a rerun isn't wanted yet.
4. **Update `MILESTONES.md`** with the "5 v1.0 tech-debt items — all now resolved/moot" addendum (details in table above).
5. **Add 1-line `HISTORICAL` banners** to `04-CONTEXT.md`, `07-01-SUMMARY.md`, `08-01-SUMMARY.md`, `08-03-SUMMARY.md`, `09-01-SUMMARY.md`, `10-01-SUMMARY.md`. Low effort, high friction reduction for new readers.
6. **Align `ROADMAP.md` progress table** (rows 170–185): Phase 13 and 14 should show `✅ Complete` in the status column to match the per-phase checkboxes above.
7. **Mark `v2.0-TECH-DEBT.md` DF-E as RESOLVED** (Messages.java deleted).
8. **Add `.gsd/README.md`** (2-line pointer to `.planning/`). Optional but tidy.
9. **`14-REVIEW.md` WR-01/02/03** — fold into a Phase 16 hygiene plan or handle inline next time those code paths are touched.

No `.planning/` or `.gsd/` edits were made by this audit; the list above is advisory, for the user or a follow-up `/gsd-docs-update` run.

---

## Verification Done by This Audit

- `@Order(*)` values confirmed against live grep: 10 (`SimulationEngine`), 14 (`EnvironmentEngine.TICK_ORDER`), 15 (`CompositeEnergyDistributor`), 20 (`ActionResolver`), 25 (`EnvPostActionReconciler.TICK_ORDER`), 50 (`TickBroadcaster`). No `@Order(100)` anywhere.
- Package tree walked: `bot/`, `codec/`, `engine/`, `metrics/`, `websocket/`, `world/`. 64 main + 69 test Java files.
- `Messages.java` absence confirmed (`grep -rn "class Messages\b" src/main/java/com/paralife/` → 0 matches).
- `PerceptionBroadcaster.java` absence confirmed (class was git-moved to `websocket/TickBroadcaster.java` in Phase 15-07).
- Micrometer meters confirmed in `metrics/WebSocketMetrics.java` (2 live, 1 deferred per SCHEMA §13).
- v1.0 tech-debt items (5) each individually checked against current code — all resolved or moot.
- 142 markdown files (`.planning/` + `.gsd/`) each have a row in this document or are covered by a bucket entry above.
