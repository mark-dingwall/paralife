# Handoff Prompt — Deep-Dive: Migrating Paralife off GSD to Superpowers / OpenSpec

> Paste everything below the line into a fresh Claude Code session opened in the
> Paralife repo. It is self-contained: it carries the context from the scoping
> discussion as **hypotheses to verify**, not facts to trust. The investigating
> session must confirm each claim against the actual repository.

---

## Role & objective

You are running a **read-only deep-dive investigation workflow** for the Paralife
project. Your job is to investigate (a) the current codebase and development
process, and (b) what transitioning the project's workflow off **GSD** and onto
each of three targets would concretely look like:

- **Option A — Superpowers** (https://github.com/obra/superpowers)
- **Option B — OpenSpec** (https://github.com/Fission-AI/OpenSpec)
- **Option C — Both** (Superpowers as the TDD execution engine + OpenSpec as the
  spec/continuity spine)

Your single deliverable is a **written report** (see *Output* below) containing
pros / cons / considerations and a **preliminary transition plan for each of the
three options**. You are NOT implementing anything and NOT committing to a choice.

## Hard constraints

- **Read-only.** Do not modify source, config, or tests. Do not commit.
- **Do NOT modify anything under `.planning/`** — the project's CLAUDE.md forbids
  editing GSD artifacts outside a GSD workflow. You may *read* them freely.
- **Verify, don't trust.** The context below is from a prior scoping session and
  parts of the repo's own docs are known to be stale. Confirm every load-bearing
  claim against the actual code and cite `file:line` evidence.
- Write only ONE new file: the final report, at the path in *Output*.

## Method — fan out, then synthesize

Run the investigation as parallel **read-only subagents** (Explore or
general-purpose), one per independent strand, then synthesize their findings into
the report yourself. Suggested strands (adjust as needed):

1. **Current-state / readiness audit.** Verify: the GSD footprint (`.planning/`
   = live GSD1 corpus; `.gsd/` = GSD2, already defunct/archived per
   `.gitignore`); the test-signal trustworthiness — the golden-trace
   byte-equivalence gate (the "three-gate stack"), any `@Disabled` tests and why,
   `forkEvery = 1` in `build.gradle.kts` and what it masks, full-suite wall-time;
   codebase modularity (records / sealed interfaces, flat packages); and the
   doc-drift between `CLAUDE.md` and `.planning/STATE.md` (e.g. milestone/phase
   mismatch, claims about REQUIREMENTS.md / `.gsd/gsd.db`).
2. **Superpowers deep-dive.** Fetch its README + CLAUDE.md + skill docs. Determine:
   install path into Claude Code (plugin/marketplace), what skills it ships, how it
   enforces RED-GREEN TDD, how its parallel-agent / subagent-driven execution
   works, persistent artifacts it keeps, language/stack assumptions (Java + Gradle
   fit), maturity & maintenance risk (single maintainer; does NOT accept skill
   contributions; auto-triggering skills). NOTE: its repo `CLAUDE.md` is an
   *upstream contribution policy* (PR rejection rules), NOT the process it imposes
   on consumers — verify this distinction.
3. **OpenSpec deep-dive.** Fetch its README + docs. Determine: the
   propose → apply → **archive** change-proposal model, the on-disk layout
   (`openspec/changes/<name>/` with proposal/specs/design/tasks), how it coexists
   with out-of-band work (non-totalizing — no global STATE to reconcile), install,
   language-agnosticism / Java fit, maturity.
4. **Migration & composition strand.** How A and B compose in Option C; how to
   cleanly retire GSD (precedent: GSD2 was archived as read-only markdown on
   2026-04-11 — the same move could apply to GSD1 `.planning/`); how the project's
   *existing* multi-review skill and its `SPEC.md` + `TEST_MAP.md` design pattern
   map onto each option; and what a concrete **first pilot** would look like under
   each (candidate pilot: a runtime tunable-knob interface — registry + actuator
   control channel + tick-boundary apply + extraction of the ~10 still-hardcoded
   behavioural constants).

## Context from prior scoping (verify all of this)

- **Paralife** is a Spring Boot + Java 21 (virtual threads) distributed living
  simulation; RPS entity dynamics on a toroidal grid; tick loop + WebSocket
  broadcast + heuristic bots. Single-threaded simulation core; all world mutation
  happens in ordered `@EventListener` tick-pipeline steps.
- **Why this investigation exists:** GSD's plan→implement→review loop has become
  too heavy/slow (e.g. "phase 20" fragmented into many subphases; roughly half of
  recent effort was reactive hardening / leak hunts; the plan-*rewrite* step acts
  as an entropy pump that manufactures the next round's review findings). The team
  wants a lighter, spec-first + TDD approach.
- **Key design principle to preserve — the mechanism/emergence split:**
  - *Mechanism* (combat math, energy decay, wire protocol, resume-token state
    machine, backpressure, env shadow-grid diffusion, the knob interface) is
    deterministic, specifiable, and **TDD-able**. The golden-trace byte-equivalence
    gate already pins it.
  - *Emergent behaviour* (population stability, spiral waves, niche formation) is
    statistical and **must NOT be pinned by tests** — it is hand-tuned via config
    knobs, with hyperparameter search a later possibility. Any chosen workflow must
    not force-test this layer. (None of the candidates force it, but confirm.)
- **Config surface (verify):** ~150 behavioural parameters are *already*
  externalized via `@ConfigurationProperties` records + `application.yml`
  (`SimulationConfig`, `MetabolicProfile` per-type, `EnvironmentConfig`,
  `BondingConfig`, `CompositeConfig`, etc.); only ~10 behavioural constants remain
  hardcoded (clustered in the nutrient/energy economy and composite/buff layer).
- **Verify-signal caveats (load-bearing for any TDD workflow):** the golden-trace
  gate is reportedly ~40% flaky *in isolation* (only trustworthy in a full-suite
  run); the two milestone-defining survival tests are `@Disabled`; `forkEvery = 1`
  masks thread leaks rather than fixing them; there is no static-analysis/lint
  gate; full `./gradlew test` is on the order of 10–25 minutes. A RED→GREEN loop is
  only as honest as these signals — assess how each option copes, and whether the
  fixes are prerequisites.

## Evaluation criteria (apply consistently across A/B/C)

1. **Non-totalizing / coexistence** — can it tolerate out-of-band work without a
   single source-of-truth STATE that drifts and demands fold-in? (This was GSD's
   core failure mode for this project.)
2. **Spec-first (SDD)** — durable spec artifact that tests pin.
3. **TDD + fan-out** — RED-GREEN discipline and parallel test-writing / subagent
   orchestration (the `TEST_MAP.md` pattern).
4. **Lightweight continuity spine** — STATE / decisions / backlog without
   plan-rewrite churn.
5. **Does NOT force-test the emergent layer.**
6. **Claude-Code-native** — skills / slash commands / subagents / plugin fit.
7. **Java + Spring + Gradle fit.**
8. **Maturity & maintenance risk.**
9. **Migration cost from GSD** (what gets archived, what gets installed, rollback).

## Output

Write a single markdown report to: `docs/workflow-migration-investigation.md`
(create `docs/` if needed; do not touch `.planning/`). Structure:

1. **Executive summary** — headline finding + a recommendation (with the caveat
   that the human has not committed).
2. **Current-state assessment** — codebase readiness, GSD footprint, verify-signal
   trustworthiness, doc-drift, with `file:line` evidence.
3. **Per-option sections (A: Superpowers, B: OpenSpec, C: Both)** — each with:
   what it is · pros · cons · considerations · a **preliminary transition plan**
   (concrete steps, what gets archived/installed, how the first pilot would run
   under it, rough effort, risks, and a rollback path).
4. **Comparison matrix** — options × the 9 criteria.
5. **Prerequisites & open questions for the human** — including whether the
   verify-signal fixes (de-flake golden gate, re-enable survival tests, leak gate)
   must precede adoption.

Keep it evidence-based and concise. Prefer tables over prose where it aids
scanning. Cite sources (repo `file:line`, and framework doc URLs).

## Source URLs

- Superpowers: https://github.com/obra/superpowers
  (README: https://raw.githubusercontent.com/obra/superpowers/refs/heads/main/README.md ,
  CLAUDE.md: https://raw.githubusercontent.com/obra/superpowers/refs/heads/main/CLAUDE.md )
- OpenSpec: https://github.com/Fission-AI/OpenSpec
  (README: https://raw.githubusercontent.com/Fission-AI/OpenSpec/refs/heads/main/README.md )
- For contrast only (already assessed as poorer fits — do not deep-dive unless
  useful): Spec Kit (github/spec-kit), BMAD-METHOD (bmad-code-org), Paul
  (ChristopherKahler/paul).
