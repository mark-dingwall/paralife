---
status: complete
phase: 15.2-own-death-event-wiring
started: 2026-04-21
completed: 2026-04-21
---

## Phase 15.2 — Own-Death Event Wiring

### Outcome

Closed the latent Phase 15 gap surfaced by 15.1 UAT Test 7: server fired 105 `DeathFinalizer` events in the combat-tight run but the wire never carried the matching `v|D` event (SCHEMA §8.4 "Died"), so `BotClient.handleDeath()` never fired and no respawn FSM ever ran. Server-side wiring added; Test 7 retried end-to-end against the live server, now passes with 414 respawns + 75 respawn-cap disconnects in a single 180s run.

### What landed (commit `4d743ce`)

| Change | File | Notes |
|--------|------|-------|
| DeathNotice queue | `engine/BotRegistry.java` | `unregisterByEntity` enqueues `DeathNotice(session, entity, lastPos)` before removing the mapping. Single unified death call site covers solo + bonded + composite-member deaths (DeathFinalizer + `SimulationEngine.cleanupCompositeMemberCellViaFinalizer`) |
| `drainDeaths()` | `engine/BotRegistry.java` | Returns-and-clears the queue; invoked once per tick by the broadcaster |
| Terminal death frame | `websocket/TickBroadcaster.java` | `onTick` drains deaths BEFORE the live-bot loop and sends a minimal-form `T\|...\|\|\|\|\|vD` to each dying bot's still-open session. Reuses the existing v-block slot — no wire-protocol change |
| Stale comment removed | `websocket/TickBroadcaster.java:636` | "plans 15-09+ wire the remaining event sources" comment deleted now that D is on the wire via the drain path (own-death needs a terminal frame; the live-bot event list never sees a dead occupant) |
| NPE fix in `markDead` | `websocket/WorldWebSocketHandler.java` | `ATTR_ENTITY_ID` cleared with `remove()` instead of `put(null)` — ConcurrentHashMap rejects null values, so the prior impl would have NPE'd if ever called. Wired via setter-injected `@Lazy` handler on TickBroadcaster to break the bean cycle |
| Bot-side observability | `bot/BotClient.java` | Added `syncCount` + `respawnCount` atomics for tests to assert respawn transitions |
| Unit test | `websocket/TickBroadcasterProjectionTest.java` | DeathNotice drain → `vD` on wire; `buildDeathFrame` shape; no-deaths path doesn't block live bots |
| Integration test | `bot/RespawnFlowIntegrationTest.java` | Forces a death via `BotRegistry` (same path the engine takes); asserts full round trip: session stays open → client sees `v\|D` → respawn `r\|` → server `S\|` with new entityId → `respawnCount > 0` |

**Test state:** 567 / 0 failures.

### UAT evidence (2026-04-21 live-server run)

Config: `./gradlew bootRun --args='--paralife.world.width=20 --paralife.world.height=20 --paralife.simulation.energy-decay-per-tick=5 --paralife.world.rock.density-threshold=255 --logging.level.com.paralife=DEBUG'` + `./gradlew runBot --args="ws://localhost:8080/ws/world 100 180"`.

| Signal | Count | Source |
|--------|-------|--------|
| Server `DeathFinalizer` events | 544 | server log |
| Server `Entity registered: ...-r1..-r5` (respawn ladder) | 414 | server log |
| Distinct respawn suffixes | `-r1`, `-r2`, `-r3`, `-r4`, `-r5` (0× `-r6`) | server log — matches `MAX_RESPAWNS_PER_SESSION = 5` |
| Bot `Bot registered:` | 514 (100 initial + 414 respawn) | bot log — matches server registrations |
| Bot `Server error 429: respawn cap exceeded` | 75 | bot log — sessions that hit the cap |
| Retry storm on 429? | no — BotClient.disconnect() on receipt (T-15-04) | bot log |
| Close codes on shutdown | 100/100 code 1000 `client disconnect` | bot log |
| Session-stable respawn? | yes — entity IDs reuse the original session UUID (e.g. `entity-4b60d948-…-r1`) | server log |

### Success criteria (from ROADMAP)

- ✅ TickBroadcaster emits `Event('D', ...)` to a bot on the tick its entity is finalized by `DeathFinalizer` — and no later, verified by the respawn round-trip latency and by `TickBroadcasterProjectionTest`.
- ✅ No wire-protocol changes — reuses the existing v-block slot (minimal-form T frame with empty c / vision / effects / pool).
- ✅ Phase 15 UAT Test 7 passes with real respawn-loop evidence — same-session UUID, `respawnCount` ladder 1..5, `E\|429` fires only at the configured cap.
- ✅ Phase 15 UAT returns to `status: complete`, `passed: 7`.

### Why the gap existed

Plan 15-08 originally sized `buildEventsForBot` around composite LOCOMOTOR alarms and left a self-documenting TODO for the other event sources. Plans 15-09 / 15-10 focused on client rewrite + metrics; Plan 15-11 focused on test migration. No plan in Phase 15 owned the own-death slot, so it slipped. 15.1's UAT retry was the first time the gap was exercised under combat for long enough for 105 deaths to land.
