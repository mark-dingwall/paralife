# `docs/` — live reference

The on-demand reference layer. `/CLAUDE.md` is the **injected, lean constitution** (loaded every
session); `docs/` holds the **live detail** that doesn't need to load every turn. Both are current
and authoritative — unlike `.planning/` and `.gsd/`, which are **frozen GSD-era history**.

## Where things live

| Layer | What | Status |
|-------|------|--------|
| `/CLAUDE.md` | Project constitution: identity, the working loop, conventions, testing philosophy, architecture map | **live, injected** |
| `docs/` (this dir) | Capability contracts + architecture internals | **live, on-demand** |
| `.planning/` | GSD-era planning corpus (phases, plans, decisions, audits) + open backlog register | **frozen** — see `.planning/README.md` |
| `.gsd/` | Older GSD2 artefacts (M001/M002) | **frozen** — see `.gsd/README.md` |

> **Do not pattern-match on the frozen corpus.** Read `.planning/` and `.gsd/` for *facts*, never
> for structure or process. How this repo is built *now* is defined in `/CLAUDE.md` §How we work.

## Capability contracts

The canonical, source-referenced specifications. Code javadoc cites these by name (e.g. "per
`SCHEMA.md` §6"). Keep them current — at PR merge, fold the change back into the relevant doc.

| Doc | Capability |
|-----|------------|
| `SCHEMA.md` | Compact-text wire protocol — frame/block grammars, bitmask layout, round-trip vectors. **Byte-exact contract.** |
| `ADMISSION.md` | Admission control, backpressure, resume-token FSM, STALLED lifecycle |
| `HARNESS.md` | External load harness, harness-identity attribution, WS:entity 1:1 connection model + design ceilings |
| `RUNTIME.md` | Per-connection runtime tuning (`paralife.runtime.*`), per-scale-tier JVM presets |

## Other

| Doc | What |
|-----|------|
| `ARCHITECTURE.md` | Deep subsystem rationale (outbound concurrency / backpressure FSM, connection model, runtime tuning) — the detail factored out of CLAUDE.md §Architecture |
| `gsd-graduation.md` | Dated decision record: why we graduated off GSD, the reject fence, open questions (archival, non-normative) |
| `leak-audit-2026-06-09.md` | Dated one-off resource-leak investigation (archival reference) |
| `BENCHMARKS.md` | Dated Phase 21 scale-benchmark evidence (100/500/1000-bot tiers) + the M4/M5/22.1 boundary statement; curated report fixtures under `benchmarks/` |
