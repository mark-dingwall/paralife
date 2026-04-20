---
status: partial
phase: 15-protocol-transport-overhaul
source: [15-01-SUMMARY.md, 15-02-SUMMARY.md, 15-03-SUMMARY.md, 15-04-SUMMARY.md, 15-05-SUMMARY.md, 15-06-SUMMARY.md, 15-07-SUMMARY.md, 15-08-SUMMARY.md, 15-09-SUMMARY.md, 15-10-SUMMARY.md, 15-11-SUMMARY.md]
started: 2026-04-20T12:58:27Z
updated: 2026-04-21T09:00:00Z
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
result: pending
evidence: "Prior evidence relied on throwaway harness since reverted; retry after Phase 15.1 lands BotRunner."

### 4. Rock Generation Determinism
expected: Set `paralife.world.rock.seed` to a NON-ZERO value (seed=0 is a sentinel for ThreadLocalRandom-derived randomness — RockGenerator.java:119-122). Boot twice with the same non-zero seed — log `Rock init placed N rocks (seed=<v>, threshold=128)` shows same N both runs. Boot with a different non-zero seed — N differs (or same N, different placement). No pre-seeded occupants overwritten.
result: pass
evidence: "3 boots via `./gradlew bootRun --args='--paralife.world.rock.seed=<v>'`: seed=42 → 32144, seed=42 → 32144 (identical), seed=99 → 32016 (diverges as expected)."

### 5. 100-Bot 50-Tick Load Gate
expected: Start bootRun. Run `./gradlew runBot --args="ws://localhost:8080/ws/world 100 30"` (100 real BotClient processes against live server for ~60 ticks at 500ms). Server log shows ~100 `Session registered` + `Entity registered` lines, no E|429 errors, no tick-skip warnings. Harness reports ≥80/100 registered, bots remain connected until harness shutdown at t=30s (clean 1000 close codes, not transport-error drops).
result: pending
evidence: "Prior evidence relied on throwaway harness since reverted; retry after Phase 15.1 lands BotRunner."

### 6. WebSocket Metrics Exposed
expected: With ≥1 bot connected, `GET /actuator/metrics/paralife.ws.active.sessions` returns a Gauge measurement equal to the live session count. `GET /actuator/metrics/paralife.ws.tick.frame.bytes` returns a DistributionSummary with non-zero count and p50/p95/p99 entries reflecting encoded pre-deflate tick-frame sizes.
result: pending
evidence: "Prior evidence relied on throwaway harness since reverted; retry after Phase 15.1 lands BotRunner."

### 7. Respawn FSM After Death
expected: Connect a bot, wait for it to take lethal damage (`v...D` event). BotClient clears `entityId`, keeps the session open, and after `respawnCooldownMs + 0..respawnJitterMs` sends a fresh `r|<species>` register. Server assigns new entityId and the bot resumes receiving tick frames. An E|429 response on respawn cap triggers disconnect (no retry storm).
result: pending
evidence: "Prior evidence relied on throwaway harness since reverted; retry after Phase 15.1 lands BotRunner."

## Summary

total: 7
passed: 3
issues: 0
pending: 4
skipped: 0
blocked: 0

## Gaps

_(Tests 3, 5, 6, 7 demoted 2026-04-21: prior evidence relied on throwaway `BotLauncher.main` + `runBot` JavaExec added and reverted during UAT. No supported operator CLI existed at the time. Phase 15.1 ships `BotRunner` as the permanent primitive; UAT retries against it.)_
