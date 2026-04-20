---
status: partial
phase: 15-protocol-transport-overhaul
source: [15-01-SUMMARY.md, 15-02-SUMMARY.md, 15-03-SUMMARY.md, 15-04-SUMMARY.md, 15-05-SUMMARY.md, 15-06-SUMMARY.md, 15-07-SUMMARY.md, 15-08-SUMMARY.md, 15-09-SUMMARY.md, 15-10-SUMMARY.md, 15-11-SUMMARY.md]
started: 2026-04-20T12:58:27Z
updated: 2026-04-21T04:30:00Z
---

## Tests

### 1. Cold Start Smoke Test
expected: Kill any running server. `./gradlew bootRun` from a clean state starts Spring Boot with the Jetty 12 container (not Tomcat). Log shows `Jetty started on port 8080`, `WebSocketRouteAssertion` passes at ApplicationReadyEvent, `RockGenerator` @PostConstruct completes without missing-PNG errors, and `TickEngine` begins ticking. `GET /actuator/health` returns 200 with status UP.
result: pass

### 2. WebSocket Deflate Negotiation (D-33)
expected: Client connecting to `/ws/world` WITHOUT `permessage-deflate; server_no_context_takeover` in its Sec-WebSocket-Extensions offer is rejected at upgrade with HTTP 400. Client connecting WITH the extension receives a 101 upgrade whose response includes the same extension token.
result: pass
evidence: "curl probe — no-deflate returned 400; with-deflate returned 101 with Sec-WebSocket-Extensions: permessage-deflate;server_no_context_takeover echoed"

### 3. Bot Connects And Ticks
expected: Launch a `BotClient` (or run `BotLauncher` test-harness). It negotiates permessage-deflate, sends `r|C` (or M/S) register frame, receives tick frames (`T|...`) decoded by `PerceptionCodec.decode`, and emits `a|<verb>|...` action frames. Server applies the action next tick (entity moves / consumes / reproduces as the HeuristicBrain decides).
result: pass
evidence: "2026-04-21 retry via `./gradlew runBot --args=\"ws://localhost:8080/ws/world 1 30\"` against live bootRun. Server log: `Session registered: 22c14d9e-469d-eebb-6c41-5abd2940eb47 (total: 1)` + `Client connected` + `Entity registered: entity-22c14d9e-... at (187,36) type=CATALYST respawnCount=null`. Bot log: `Bot connected: species=C` + `Bot registered: entity=entity-22c14d9e-... species=C`. Duration-mode exit at t=30s triggered shutdown hook → `Bot disconnected` (code 1000 client disconnect) → `Session unregistered (total: 0)`. Codec round trip (encoded r|C → decoded server-side → encoded entity-registered reply → decoded bot-side) works end-to-end; session survived keepalive window without timeout."

### 4. Rock Generation Determinism
expected: Set `paralife.world.rock.seed` to a NON-ZERO value (seed=0 is a sentinel for ThreadLocalRandom-derived randomness — RockGenerator.java:119-122). Boot twice with the same non-zero seed — log `Rock init placed N rocks (seed=<v>, threshold=128)` shows same N both runs. Boot with a different non-zero seed — N differs (or same N, different placement). No pre-seeded occupants overwritten.
result: pass
evidence: "3 boots via `./gradlew bootRun --args='--paralife.world.rock.seed=<v>'`: seed=42 → 32144, seed=42 → 32144 (identical), seed=99 → 32016 (diverges as expected)."

### 5. 100-Bot 50-Tick Load Gate
expected: Start bootRun. Run `./gradlew runBot --args="ws://localhost:8080/ws/world 100 30"` (100 real BotClient processes against live server for ~60 ticks at 500ms). Server log shows ~100 `Session registered` + `Entity registered` lines, no E|429 errors, no tick-skip warnings. Harness reports ≥80/100 registered, bots remain connected until harness shutdown at t=30s (clean 1000 close codes, not transport-error drops).
result: pass
evidence: "2026-04-21 retry via `./gradlew runBot --args=\"ws://localhost:8080/ws/world 100 30\"` against live bootRun. Server log in window: `Session registered` × 100; `Entity registered:` × 100 (all respawnCount=null, types balanced C/M/S); `E|429` × 0; `tick-skip` / `warn` / `error` × 0. Every client disconnected with CloseStatus code=1000 'client disconnect' (100/100). No non-1000 close codes. 100% registered exceeds the ≥80/100 threshold by 20 points."

### 6. WebSocket Metrics Exposed
expected: With ≥1 bot connected, `GET /actuator/metrics/paralife.ws.active.sessions` returns a Gauge measurement equal to the live session count. `GET /actuator/metrics/paralife.ws.tick.frame.bytes` returns a DistributionSummary with non-zero count and p50/p95/p99 entries reflecting encoded pre-deflate tick-frame sizes.
result: pass
evidence: "2026-04-21 retry concurrent with Test 5 at t~10s (100 bots active): `curl /actuator/metrics/paralife.ws.active.sessions` → `{\"statistic\":\"VALUE\",\"value\":100.0}` (matches live bot count exactly). `curl /actuator/metrics/paralife.ws.tick.frame.bytes` → `COUNT=1186.0, TOTAL=173437.0, MAX=176.0` bytes (DistributionSummary populated with realistic pre-deflate sizes — mean≈146 bytes matches Phase 15 plan 08 envelope)."

### 7. Respawn FSM After Death
expected: Connect a bot, wait for it to take lethal damage (`v...D` event). BotClient clears `entityId`, keeps the session open, and after `respawnCooldownMs + 0..respawnJitterMs` sends a fresh `r|<species>` register. Server assigns new entityId and the bot resumes receiving tick frames. An E|429 response on respawn cap triggers disconnect (no retry storm).
result: issue
evidence: "2026-04-21 retry found a server-side wire-protocol gap that blocks this test from ever passing as currently implemented. Combat-tight run (20x20 grid, 0 rocks, energy-decay=5, 100 bots × 180s) via `./gradlew runBot --args=\"ws://localhost:8080/ws/world 100 180\" --paralife.world.width=20 --paralife.world.height=20 --paralife.simulation.energy-decay-per-tick=5 --paralife.world.rock.density-threshold=255` produced 105 server-side `DeathFinalizer: Particle death finalised` events (DEBUG log) — but zero bot-side respawns: `Entity registered: ... -rN` count = 0, E|429 count = 0. Root cause located: `TickBroadcaster.buildEventsForBot` (src/main/java/com/paralife/websocket/TickBroadcaster.java:636) only drains composite LOCOMOTOR alarms; no own-damage or own-death `Event('D', ...)` is ever emitted onto the v-block. The in-file comment openly flags this: 'those events are produced but not projected onto the wire in plan 15-08. Plans 15-09+ wire the remaining event sources; this slot is ready.' That wiring was never completed. BotClient.java:243 death check `t.events().stream().anyMatch(ev -> ev.code() == 'D')` therefore never fires. Respawn FSM is untestable end-to-end today — server kills the entity (grid cleanup + BotRegistry.unregisterByEntity) but the owning session keeps receiving tick frames forever with no death signal. Fix scope is server-side event-source wiring (not transport, not codec, not bot FSM) and belongs in a new Phase 15.2 or Phase 16 input, per Phase 15.1 plan's 'exit ramp' clause. This is NOT an artefact of the throwaway-harness revert — it is a latent gap in plan 15-08 that the throwaway harness never exercised long enough to expose."

## Summary

total: 7
passed: 6
issues: 1
pending: 0
skipped: 0
blocked: 0

## Gaps

_(Test 7 revealed a latent Phase 15 gap: server emits no `v|D` own-death event onto the wire, so the respawn FSM cannot be exercised end-to-end even though the DeathFinalizer correctly unregisters dying entities server-side. See Test 7 evidence for exact source pointer (`TickBroadcaster.java:636`) and rationale. Fix is out of Phase 15.1 scope — Phase 15.1 explicitly excludes changes to wire protocol / codec / respawn FSM. Tracked for Phase 15.2 (or Phase 16 input if that's where it lands after discussion).)_
