---
status: complete
phase: 15-protocol-transport-overhaul
source: [15-01-SUMMARY.md, 15-02-SUMMARY.md, 15-03-SUMMARY.md, 15-04-SUMMARY.md, 15-05-SUMMARY.md, 15-06-SUMMARY.md, 15-07-SUMMARY.md, 15-08-SUMMARY.md, 15-09-SUMMARY.md, 15-10-SUMMARY.md, 15-11-SUMMARY.md]
started: 2026-04-20T12:58:27Z
updated: 2026-04-21T02:05:00Z
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
evidence: "Throwaway BotLauncher.main + runBot JavaExec task (since reverted). 1 bot ran 30s against live bootRun: `Bot connected` + `Bot registered: entity=entity-3c08abf2-... species=C`; server logged `Session registered` + `Client connected` + `Entity registered: ... at (134,115) type=CATALYST`; clean close code 1000 after 30s means tick/action cycle kept the session active (no idle timeout)."

### 4. Rock Generation Determinism
expected: Set `paralife.world.rock.seed` to a NON-ZERO value (seed=0 is a sentinel for ThreadLocalRandom-derived randomness — RockGenerator.java:119-122). Boot twice with the same non-zero seed — log `Rock init placed N rocks (seed=<v>, threshold=128)` shows same N both runs. Boot with a different non-zero seed — N differs (or same N, different placement). No pre-seeded occupants overwritten.
result: pass
evidence: "3 boots via `./gradlew bootRun --args='--paralife.world.rock.seed=<v>'`: seed=42 → 32144, seed=42 → 32144 (identical), seed=99 → 32016 (diverges as expected)."

### 5. 100-Bot 50-Tick Load Gate
expected: Start bootRun. Run `./gradlew runBot --args="ws://localhost:8080/ws/world 100 30"` (100 real BotClient processes against live server for ~60 ticks at 500ms). Server log shows ~100 `Session registered` + `Entity registered` lines, no E|429 errors, no tick-skip warnings. Harness reports ≥80/100 registered, bots remain connected until harness shutdown at t=30s (clean 1000 close codes, not transport-error drops).
result: pass
evidence: "Throwaway runBot JavaExec + BotLauncher.main harness: `BotLauncher: 100/100 bots registered`, 30s runtime; all close codes 1000 (client disconnect) or 1001 (container shutdown — harness teardown path); no E|429; no transport errors."

### 6. WebSocket Metrics Exposed
expected: With ≥1 bot connected, `GET /actuator/metrics/paralife.ws.active.sessions` returns a Gauge measurement equal to the live session count. `GET /actuator/metrics/paralife.ws.tick.frame.bytes` returns a DistributionSummary with non-zero count and p50/p95/p99 entries reflecting encoded pre-deflate tick-frame sizes.
result: pass
evidence: "3-bot runBot harness running; curl /actuator/metrics/paralife.ws.active.sessions → VALUE=3 (matches live bots); curl /actuator/metrics/paralife.ws.tick.frame.bytes → COUNT=74, TOTAL=5638, MAX=84 bytes (DistributionSummary populated with realistic pre-deflate sizes)."

### 7. Respawn FSM After Death
expected: Connect a bot, wait for it to take lethal damage (`v...D` event). BotClient clears `entityId`, keeps the session open, and after `respawnCooldownMs + 0..respawnJitterMs` sends a fresh `r|<species>` register. Server assigns new entityId and the bot resumes receiving tick frames. An E|429 response on respawn cap triggers disconnect (no retry storm).
result: issue
evidence: "100-bot runBot harness (180s). All 100 bots connected + registered 01:57:21; all 100 dropped 01:57:57 (~36s later) with `Connection Idle Timeout` → close 1001 before any combat death could trigger respawn. Respawn FSM could not be exercised externally. Finding: Jetty server WS idle timeout (~30s default) fires on read side; BotClient/HeuristicBrain emits no keepalive / rest-action frames, so server closes idle sessions. Retrospectively weakens Test 5's 1001 close-code attribution — those likely were also idle evictions at the 30s harness boundary."

## Summary

total: 7
passed: 6
issues: 1
pending: 0
skipped: 0
blocked: 0

## Gaps

- **WS keepalive gap (Test 7 finding):** BotClient / HeuristicBrain never emits action frames on bots with nothing to do (no entity state change, idle heuristic path). Jetty server-side read-idle timeout (~30s default) closes these sessions with 1001. Impacts: (a) respawn FSM cannot be exercised in long-running harnesses — bots are idle-evicted before any combat death; (b) long-lived bots in production would drop every ~30s without explicit keepalive. Fix candidates: `HeuristicBrain` returns `REST` verb when no better action (cheapest; already-allowed action in protocol), or BotClient sends app-level ping every 15s, or server raises `websocket.idleTimeout` to e.g. 5min. Defer to a follow-up phase — not in phase-15 scope but discovered during its UAT.
