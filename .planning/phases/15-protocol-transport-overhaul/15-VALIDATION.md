---
phase: 15
slug: protocol-transport-overhaul
status: approved
nyquist_compliant: true
wave_0_complete: true
created: 2026-04-20
---

# Phase 15 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (unit + integration) |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests 'com.paralife.codec.*' --tests 'com.paralife.world.RockGenerator*'` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~90 seconds full suite |

---

## Sampling Rate

- **After every task commit:** Run the quick command scoped to the package touched by the task
- **After every plan wave:** Run full suite
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~90 seconds

---

## Per-Task Verification Map

Populated by planner. Mirrors every task's `<verify><automated>` command from the 11 plan files. Covers these phase verification dimensions (see §Validation Architecture in 15-RESEARCH.md):

1. **Codec round-trip** — 13 locked vectors from 15-SCHEMA.md §10 (`PerceptionCodecRoundTripTest`, `@ParameterizedTest`).
2. **Handshake extension negotiation** (D-32) — raw HTTP upgrade asserts `Sec-WebSocket-Extensions` contains `permessage-deflate; server_no_context_takeover`.
3. **Fail-fast enforcement** (D-33) — server refuses upgrade when client omits extension; client closes session when server doesn't echo it.
4. **Existing test migration** (ROADMAP line 139) — all 166 existing tests still pass under new wire protocol.
5. **Stateless bot reachability** — fixed-seed replay produces identical action decisions from identical decoded frame sequence (pure-function `HeuristicBrain`).
6. **Rock generation determinism** (D-35) — same `paralife.world.rock-seed` → byte-identical rock grid.
7. **Actuator metrics** (D-38) — `paralife.ws.active-sessions` gauge, `paralife.ws.tick-frame-bytes` distribution summary, `paralife.ws.bytes-saved` counter reachable and monotonically valid after N ticks.
8. **Zero-trust projection** — server only emits data derivable from bot's sensor radius; assertion test covers mixed-radius scenarios.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 15-01-T1 | 15-01 | 1 | R20-R29 | — | Planning artifact bookkeeping (D-47/D-03) | grep | `! grep -n "Precompress fan-out infrastructure" .planning/ROADMAP.md && grep -qc "Phase 15: Protocol & Transport Overhaul" .planning/ROADMAP.md` | ✅ exists | ⬜ pending |
| 15-01-T2 | 15-01 | 1 | R20-R29 | — | R20-R29 IDs introduced; R15-R19 remapped | grep | `grep -c "^\| R2[0-9] \|" .planning/REQUIREMENTS.md \| grep -qx 10 && grep -qE "^\| R15 \|.*\| 16 \|" .planning/REQUIREMENTS.md` | ✅ exists | ⬜ pending |
| 15-02-T1 | 15-02 | 1 | R20, R21 | T-15-01 | Pure-Java codec scaffold (no Spring/Jackson) | unit | `./gradlew compileJava --console=plain -q && test -f src/main/java/com/paralife/codec/Base64Codec.java && test -f src/main/java/com/paralife/codec/CodecException.java && test -f src/main/java/com/paralife/codec/ParseCursor.java && ! grep -rE "org\.springframework\|com\.fasterxml\.jackson" src/main/java/com/paralife/codec/` | ❌ Wave 0 | ⬜ pending |
| 15-02-T2 | 15-02 | 1 | R20, R21 | T-15-01 | Sealed Frame hierarchy w/ canonical-ctor validation | unit | `./gradlew compileJava --console=plain -q && test -f src/main/java/com/paralife/codec/Frame.java && test -f src/main/java/com/paralife/codec/PerceptionCodec.java && ! grep -rE "org\.springframework\|com\.fasterxml\.jackson" src/main/java/com/paralife/codec/ && grep -q "UnsupportedOperationException" src/main/java/com/paralife/codec/PerceptionCodec.java` | ❌ Wave 0 | ⬜ pending |
| 15-02-T3 | 15-02 | 1 | R20, R21 | T-15-01 | RED 13-vector round-trip oracle (TDD seed) | unit (param) | `./gradlew compileTestJava --console=plain -q && test -f src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java && test "$(grep -cE '// V[0-9]+' src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java)" = "13" && grep -q 's43R824,124,-1-124' src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java && ./gradlew test --tests 'com.paralife.codec.PerceptionCodecRoundTripTest' --console=plain 2>&1 \| grep -q "FAILED\|UnsupportedOperationException"` | ❌ Wave 0 | ⬜ pending |
| 15-03-T1 | 15-03 | 1 | R22 | T-15-02 | Tomcat→Jetty 12 container swap | gradle | `./gradlew dependencyInsight --dependency org.eclipse.jetty:jetty-server --configuration runtimeClasspath --console=plain 2>&1 \| grep -q "org.eclipse.jetty:jetty-server" && ! ./gradlew dependencies --configuration runtimeClasspath --console=plain 2>&1 \| grep -q "spring-boot-starter-tomcat" && ./gradlew dependencies --configuration runtimeClasspath --console=plain 2>&1 \| grep -q "spring-boot-starter-jetty"` | ❌ Wave 0 | ⬜ pending |
| 15-03-T2 | 15-03 | 1 | R22, R24 | T-15-02 | permessage-deflate + server_no_context_takeover enforced (D-33) | unit | `./gradlew compileJava --console=plain -q && test -f src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java && grep -q "permessage-deflate" src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java && grep -q "server_no_context_takeover" src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java && grep -q "addMapping" src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java` | ❌ Wave 0 | ⬜ pending |
| 15-03-T3 | 15-03 | 1 | R23, R24 | T-15-02 | Handshake negotiation + refusal-without-deflate | integration | `./gradlew test --tests 'com.paralife.websocket.WebSocketDeflateHandshakeIntegrationTest' --tests 'com.paralife.websocket.ServerRefusesUpgradeWithoutDeflateTest' --console=plain` | ❌ Wave 0 | ⬜ pending |
| 15-04-T1 | 15-04 | 1 | R28 | — | RockConfig validation + defaults | unit | `./gradlew test --tests 'com.paralife.world.RockConfigTest' --console=plain -q` | ❌ Wave 0 | ⬜ pending |
| 15-04-T2 | 15-04 | 1 | R28 | — | Determinism + idempotence + 5 PNG resources present | unit | `./gradlew test --tests 'com.paralife.world.RockGeneratorTest' --console=plain -q && for i in 01 02 03 04 05; do test -s "src/main/resources/rocks/perlin-$i.png" \|\| exit 1; done` | ❌ Wave 0 | ⬜ pending |
| 15-05-T1 | 15-05 | 2 | R20, R21 | T-15-01, T-15-03 | All 13 SCHEMA §10 vectors round-trip byte-for-byte | unit (param) | `./gradlew test --tests 'com.paralife.codec.PerceptionCodecRoundTripTest' --console=plain -q` | ❌ Wave 0 | ⬜ pending |
| 15-05-T2 | 15-05 | 2 | R20, R21 | T-15-01 | Malformed input + DoS-bomb rejection | unit | `./gradlew test --tests 'com.paralife.codec.PerceptionCodecErrorTest' --console=plain -q` | ❌ Wave 0 | ⬜ pending |
| 15-06-T1 | 15-06 | 3 | R20, R26, R27 | T-15-01, T-15-04 | Codec-driven handler + respawn FSM + cap | grep+compile | `./gradlew compileJava --console=plain -q && ! grep -q "ObjectMapper" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java && ! grep -q "import com.paralife.websocket.Messages" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java && grep -q "PerceptionCodec.decode" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java && grep -q "PerceptionCodec.encode" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java && grep -q "MAX_RESPAWNS_PER_SESSION" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` | ❌ Wave 0 | ⬜ pending |
| 15-06-T2 | 15-06 | 3 | R20, R25 | T-15-03 | IRV vote resolver + Frame.ActionFrame dispatch + Messages stripped | grep+compile | `./gradlew compileJava --console=plain -q && grep -q "Frame.ActionFrame" src/main/java/com/paralife/engine/ActionResolver.java && grep -qi "IRV\|instant.runoff\|elimination" src/main/java/com/paralife/engine/ActionResolver.java && grep -q "fromNumpad" src/main/java/com/paralife/engine/Direction.java && ! grep -rE "import com\.paralife\.websocket\.Messages" src/main/java/ 2>/dev/null` | ❌ Wave 0 | ⬜ pending |
| 15-06-T3 | 15-06 | 3 | R20, R25, R27 | T-15-01, T-15-04 | Handler FSM + IRV resolver unit-tested | unit | `./gradlew test --tests 'com.paralife.engine.IRVVoteResolverTest' --tests 'com.paralife.websocket.WorldWebSocketHandlerTest' --console=plain -q` | ❌ Wave 0 | ⬜ pending |
| 15-07-T1 | 15-07 | 4 | R20 | — | Old heartbeat broadcaster + paired test deleted | grep | `! test -f src/main/java/com/paralife/websocket/TickBroadcaster.java && ! test -f src/test/java/com/paralife/websocket/TickBroadcasterTest.java` | ✅ exists (pre-Phase-15) | ⬜ pending |
| 15-07-T2 | 15-07 | 4 | R20, R25 | T-15-03 | engine.PerceptionBroadcaster → websocket.TickBroadcaster rename; OVERCROWDED mask preserved | grep+compile | `! test -f src/main/java/com/paralife/engine/PerceptionBroadcaster.java && test -f src/main/java/com/paralife/websocket/TickBroadcaster.java && grep -q "class TickBroadcaster" src/main/java/com/paralife/websocket/TickBroadcaster.java && grep -q "package com.paralife.websocket" src/main/java/com/paralife/websocket/TickBroadcaster.java && grep -q "@Order(50)" src/main/java/com/paralife/websocket/TickBroadcaster.java && grep -q "cached & ~BIT_OVERCROWDED" src/main/java/com/paralife/websocket/TickBroadcaster.java && ! grep -rE "import com\.paralife\.engine\.PerceptionBroadcaster" src/main/ src/test/ 2>/dev/null` | ✅ exists (pre-Phase-15) | ⬜ pending |
| 15-08-T1 | 15-08 | 5 | R20, R25 | T-15-03 | Codec frame projection + authority tiers + zero-trust kind codes | grep+compile | `./gradlew compileJava --console=plain -q && ! grep -q "ObjectMapper" src/main/java/com/paralife/websocket/TickBroadcaster.java && ! grep -qE "import com\.paralife\.websocket\.Messages" src/main/java/com/paralife/websocket/TickBroadcaster.java && grep -q "PerceptionCodec.encode" src/main/java/com/paralife/websocket/TickBroadcaster.java && grep -q "@Order(50)" src/main/java/com/paralife/websocket/TickBroadcaster.java && grep -q "cached & ~BIT_OVERCROWDED" src/main/java/com/paralife/websocket/TickBroadcaster.java` | ❌ Wave 0 | ⬜ pending |
| 15-08-T2 | 15-08 | 5 | R20, R25 | T-15-03 | FLEEING applier + alarm-routing wired | grep+compile | `./gradlew compileJava --console=plain -q && (grep -q "FLEEING\|Fleeing" src/main/java/com/paralife/engine/EnvironmentEngine.java \|\| grep -q "FLEEING\|Fleeing" src/main/java/com/paralife/engine/BuffRegistry.java) && grep -q "alarm\|Alarm" src/main/java/com/paralife/websocket/TickBroadcaster.java` | ❌ Wave 0 | ⬜ pending |
| 15-08-T3 | 15-08 | 5 | R25 | T-15-03 | Zero-trust projection: no entity ids on wire | unit (Spring) | `./gradlew test --tests 'com.paralife.engine.ZeroTrustFilteringTest' --console=plain -q` | ❌ Wave 0 | ⬜ pending |
| 15-09-T1 | 15-09 | 6 | R24, R26, R27 | T-15-02, T-15-04 | Jetty-native client + codec + respawn FSM + D-33 client-side enforcement | grep+compile | `./gradlew compileJava --console=plain -q && ! grep -q "com.fasterxml.jackson" src/main/java/com/paralife/bot/BotClient.java && ! grep -q "org.springframework.web.socket.client.standard.StandardWebSocketClient" src/main/java/com/paralife/bot/BotClient.java && grep -q "permessage-deflate" src/main/java/com/paralife/bot/BotClient.java && grep -q "PerceptionCodec.encode" src/main/java/com/paralife/bot/BotClient.java && grep -q "PerceptionCodec.decode" src/main/java/com/paralife/bot/BotClient.java && grep -q "handleDeath\|respawn" src/main/java/com/paralife/bot/BotClient.java` | ❌ Wave 0 | ⬜ pending |
| 15-09-T2 | 15-09 | 6 | R26 | — | HeuristicBrain pure-function + dead-branch fix + determinism test | unit | `./gradlew test --tests 'com.paralife.bot.HeuristicBrainDeterminismTest' --console=plain -q && grep -q "Frame.TickFrame" src/main/java/com/paralife/bot/HeuristicBrain.java && ! grep -q "preyType.predator() == myType" src/main/java/com/paralife/bot/HeuristicBrain.java` | ❌ Wave 0 | ⬜ pending |
| 15-09-T3 | 15-09 | 6 | R24, R27 | T-15-02, T-15-04 | D-33 client-side close + respawn flow integration | integration | `./gradlew test --tests 'com.paralife.bot.BotClientClosesOnMissingServerDeflateTest' --tests 'com.paralife.bot.RespawnFlowIntegrationTest' --console=plain -q` | ❌ Wave 0 | ⬜ pending |
| 15-10-T1 | 15-10 | 6 | R29 | — | WebSocketMetrics bean (3 meters, dot-separated names) | grep+compile | `./gradlew compileJava --console=plain -q && test -f src/main/java/com/paralife/metrics/WebSocketMetrics.java && grep -q "paralife.ws.bytes.saved" src/main/java/com/paralife/metrics/WebSocketMetrics.java && grep -q "paralife.ws.active.sessions" src/main/java/com/paralife/metrics/WebSocketMetrics.java && grep -q "paralife.ws.tick.frame.bytes" src/main/java/com/paralife/metrics/WebSocketMetrics.java` | ❌ Wave 0 | ⬜ pending |
| 15-10-T2 | 15-10 | 6 | R29 | — | Meters fed by SessionRegistry + TickBroadcaster | grep+compile | `./gradlew compileJava --console=plain -q && grep -q "metrics.setActiveSessions\|setActiveSessions" src/main/java/com/paralife/websocket/SessionRegistry.java && grep -q "metrics.recordFrameSize\|recordFrameSize" src/main/java/com/paralife/websocket/TickBroadcaster.java` | ❌ Wave 0 | ⬜ pending |
| 15-10-T3 | 15-10 | 6 | R29 | — | All 3 meters reachable via /actuator/metrics | integration (Spring) | `./gradlew test --tests 'com.paralife.websocket.MetricsEndpointIntegrationTest' --console=plain -q` | ❌ Wave 0 | ⬜ pending |
| 15-11-T1 | 15-11 | 7 | R20, R26 | — | Wire integration tests migrated to codec | integration | `./gradlew test --tests 'com.paralife.websocket.WebSocketIntegrationTest' --tests 'com.paralife.websocket.HundredBotIntegrationTest' --tests 'com.paralife.bot.BotClientIntegrationTest' --console=plain -q && ! grep -rqE "com\.paralife\.websocket\.Messages" src/test/java/com/paralife/websocket/ src/test/java/com/paralife/bot/` | ✅ exists (pre-Phase-15) | ⬜ pending |
| 15-11-T2 | 15-11 | 7 | R20, R25 | — | Engine-side broadcaster + composite tests migrated | unit+integration | `./gradlew test --tests 'com.paralife.websocket.TickBroadcasterProjectionTest' --tests 'com.paralife.engine.PerceptionActionIntegrationTest' --tests 'com.paralife.engine.CompositeActionTest' --tests 'com.paralife.engine.CompositePerceptionTest' --tests 'com.paralife.engine.CompositeMovementTest' --console=plain -q` | ✅ exists (pre-Phase-15) | ⬜ pending |
| 15-11-T3 | 15-11 | 7 | R20, R26 | — | Full-suite gate (LoadTest + PopulationDynamicsTest + everything) | full suite | `./gradlew test --console=plain -q` | ✅ exists (pre-Phase-15) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

**Sampling continuity check:** 29 tasks; 0 task without an automated `<verify>`. No 3-consecutive-task automation gap.

---

## Wave 0 Requirements

- [x] `src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java` — 13-vector round-trip from 15-SCHEMA.md §10 (seeded RED in plan 15-02 Task 3, GREEN after plan 15-05 Task 1)
- [x] `src/test/java/com/paralife/codec/PerceptionCodecErrorTest.java` — malformed-input + DoS-safety (plan 15-05 Task 2)
- [x] `src/test/java/com/paralife/websocket/WebSocketDeflateHandshakeIntegrationTest.java` — Sec-WebSocket-Extensions response header assertion (plan 15-03 Task 3)
- [x] `src/test/java/com/paralife/websocket/ServerRefusesUpgradeWithoutDeflateTest.java` — D-33 server-side enforcement (plan 15-03 Task 3)
- [x] `src/test/java/com/paralife/world/RockConfigTest.java` (plan 15-04 Task 1) and `src/test/java/com/paralife/world/RockGeneratorTest.java` (plan 15-04 Task 2)
- [x] `src/test/java/com/paralife/engine/IRVVoteResolverTest.java` + `src/test/java/com/paralife/websocket/WorldWebSocketHandlerTest.java` (plan 15-06 Task 3)
- [x] `src/test/java/com/paralife/engine/ZeroTrustFilteringTest.java` (plan 15-08 Task 3)
- [x] `src/test/java/com/paralife/bot/HeuristicBrainDeterminismTest.java` (plan 15-09 Task 2)
- [x] `src/test/java/com/paralife/bot/BotClientClosesOnMissingServerDeflateTest.java` + `src/test/java/com/paralife/bot/RespawnFlowIntegrationTest.java` (plan 15-09 Task 3)
- [x] `src/test/java/com/paralife/websocket/MetricsEndpointIntegrationTest.java` (plan 15-10 Task 3)
- [x] Existing JUnit + Spring Boot Test + Jetty 12 dependency — no new framework install required

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| *(none anticipated; all phase behaviour is deterministic and testable)* | | | |

All phase behaviors have automated verification.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved
