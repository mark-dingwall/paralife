---
title: External load harness as deployment primitive
trigger: M4 entry
planted: 2026-04-21
source_phase: 15.1-bot-operator-cli
status: seeded
---

## Summary

`BotRunner` (Phase 15.1) is the single-process operator CLI, capped at 100 bots per JVM. M4 needs the real thing: a first-class external load harness that scales past that cap as a deployable component, not a throwaway test-harness pattern.

## Why this is M4 and not earlier

The v1.0/v2.0 milestone success criteria validate up to 100 concurrent bots per single-JVM server. Phase 15.1 deliberately enforces that ceiling at the CLI boundary (`BotRunner.MAX_BOTS = 100`) to force the scale conversation to happen at a milestone boundary rather than silently drift. M4 ("Scale & Hardening", tentative) is where the simulation itself is expected to be validated past the 100-bot envelope — the harness is that work's natural peer.

## Scope (when this seed germinates)

- **Harness-ID protocol**: each bot registers with a harness-origin identifier so server-side can partition observability by harness instance (and detect rogue harnesses in shared environments)
- **Per-harness metrics**: Micrometer tags on `paralife.ws.active.sessions` / `tick.frame.bytes` / any future action-throughput meters so the harness can be shed/diagnosed without log spelunking
- **Cross-process respawn semantics**: Phase 15 respawn FSM assumes a single process per session; multi-process harnesses need respawn-storm bounds across the fleet, not per-process
- **World-partition-aware bot placement**: at 1000+ bots on a 256×256 grid, random initial placement collides heavily with rocks and existing entities — harness should accept seed-region hints or cooperate with a server-side placement API
- **1000+ scale**: concrete target is 1000 bots sustained for 10 minutes with no tick-drift regression vs the 100-bot Phase 15 baseline
- **Deployment primitive**: harness runs in its own container/process, not as a `./gradlew` task. CLI contract, not build-system contract.

## Explicit hand-off point from Phase 15.1

`src/main/java/com/paralife/bot/BotRunner.java` — the `MAX_BOTS = 100` constant and its guardrail message are the explicit boundary. When M4 picks this up, that hard cap either stays (BotRunner remains the local-dev CLI) or gets lifted (BotRunner is deprecated in favour of the harness). The plan expects the former — small-N operator workflows stay useful even when the harness exists.

## Reference artefacts

- `src/main/java/com/paralife/bot/BotRunner.java` — class-level Javadoc documents the non-goals list that this seed inverts
- `.planning/phases/15.1-bot-operator-cli/SUMMARY.md` — closeout notes
- `.planning/ROADMAP.md` Phase 15.1 success criteria — final bullet plants this seed explicitly
