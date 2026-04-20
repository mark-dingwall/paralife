---
status: complete
phase: 15.1-bot-operator-cli
started: 2026-04-21
completed: 2026-04-21
---

## Phase 15.1 — Bot Operator CLI

### Outcome

Shipped `BotRunner` + `runBot` Gradle task as the permanent single-process operator CLI for launching bots against a live server. Retried Phase 15 UAT Tests 3/5/6/7 with real evidence against the new primitive. Tests 3/5/6 recovered cleanly; Test 7 surfaced a latent Phase 15 server-side gap that was out of this phase's scope — documented and handed off to Phase 15.2.

### What landed

| Artifact | Path | Notes |
|----------|------|-------|
| BotRunner main class | `src/main/java/com/paralife/bot/BotRunner.java` | Thin glue over BotLauncher; arg parsing, 100-bot hard cap, shutdown hook, optional duration mode |
| `runBot` Gradle task | `build.gradle.kts` | `group = "application"`; forwards `paralife.*` system properties |
| CLAUDE.md correction | `CLAUDE.md` architecture table | `com.paralife.bot` row no longer labels BotLauncher "test-only, not deployed" |
| UAT retry | `.planning/phases/15-protocol-transport-overhaul/15-UAT.md` | Tests 3/5/6 pass with real bootRun evidence; Test 7 → `issue` with full root-cause trace |
| M4 seed | `.planning/seeds/m4-external-load-harness.md` | "External load harness as deployment primitive" |

### Guardrail behaviour

- `count > 100` exits 1 with: `count=<N> exceeds validated envelope (max=100). … For 1000+ scale use the M4 external load harness, not BotRunner.`
- `count < 1`, missing args, non-integer args all exit 1 with usage output
- Ctrl-C / SIGTERM triggers `BotLauncher.shutdown()` via Runtime shutdown hook → all bots close with WebSocket code 1000

### UAT retry results (against BotRunner)

| Test | Verdict | Key evidence |
|------|---------|--------------|
| 3. Bot Connects And Ticks | pass | `./gradlew runBot 1 30` — Session registered, Entity registered at (187,36) type=CATALYST, Bot registered entity=..., clean close 1000 |
| 5. 100-Bot Load Gate | pass | `./gradlew runBot 100 30` — 100/100 registered, 0 E|429, 100 close-code-1000, 0 non-1000 closes, 0 warnings |
| 6. WS Metrics | pass | `paralife.ws.active.sessions` VALUE=100.0 concurrent with Test 5; `tick.frame.bytes` COUNT=1186 TOTAL=173437 MAX=176 |
| 7. Respawn FSM | issue | Combat-tight run (20×20, 0 rocks, decay=5, 180s): 105 server-side DeathFinalizer events, 0 bot-side respawns. Root cause: `TickBroadcaster.buildEventsForBot` (line 636) never emits own-death `v|D` onto wire — Plan 15-08 comment already flagged "plans 15-09+ wire the remaining event sources" but wiring was never completed |

### Phase 15.2 handoff

Test 7 blocker is a server-side event-source wiring gap — not a transport, codec, or FSM change. Fix belongs in a follow-up phase. ROADMAP.md Phase 15.2 row inserted with the tight scope and Test 7 as the acceptance gate.

### Non-goals (explicit, documented in `BotRunner` Javadoc)

- Multi-process coordination / harness-ID protocol
- Per-harness metrics
- Cross-process respawn semantics
- World-partition-aware bot placement
- Bot counts beyond 100

All of the above are M4 scope (see `.planning/seeds/m4-external-load-harness.md`). The 100-bot cap forces the scale conversation to happen at the M4 boundary rather than silently drift.

### Sequencing landed

1. Task 5a (demote UAT) — commit e6bb7e3
2. Tasks 1-4 (BotRunner + gradle + CLAUDE.md) — commit 4375065
3. Task 5b (UAT retry + Phase 15.2 handoff) + Task 5c (close) + Task 6 (M4 seed) — this commit
