---
phase: "08"
plan: "01"
---

# T01: BotRegistry, Perception/Action message types, and PerceptionBroadcaster — all wired and tested.

> BotRegistry, Perception/Action message types, and PerceptionBroadcaster — all wired and tested.

## What Happened
---
id: T01
parent: S03
milestone: M002
key_files:
  - src/main/java/com/paralife/engine/BotRegistry.java
  - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
  - src/main/java/com/paralife/websocket/Messages.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
  - src/test/java/com/paralife/engine/BotRegistryTest.java
  - src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java
key_decisions:
  - PerceptionBroadcaster at @Order(50) — after SimulationEngine(10), before TickBroadcaster(100)
  - 5×5 neighbourhood (radius 2) as List<List<CellView>> for JSON serialization
  - CellView uses string occupantType (CATALYST/MEMBRANE/SPORE/ROCK/NUTRIENT/null) for simplicity
  - BotRegistry uses ConcurrentHashMap with bidirectional sessionId↔entityId mapping
duration: ""
verification_result: passed
completed_at: 2026-04-01T14:42:59.739Z
blocker_discovered: false
---

# T01: BotRegistry, Perception/Action message types, and PerceptionBroadcaster — all wired and tested.

**BotRegistry, Perception/Action message types, and PerceptionBroadcaster — all wired and tested.**

## What Happened

Created BotRegistry mapping sessionId↔entityId↔Position with concurrent-safe ConcurrentHashMap. Extended Messages sealed interface with Perception (entity state + 5×5 neighbourhood CellView grid), Action (move/consume/reproduce/rest with direction), ActionResult, and EntityState/CellView view records. Built PerceptionBroadcaster at @Order(50) that sends each registered bot their local neighbourhood each tick. Updated WorldWebSocketHandler to inject BotRegistry, register bots in it on Register, clean up on disconnect, and accept Action messages (queued for T02's ActionResolver). Fixed a pre-existing timing flake in HundredBotIntegrationTest by snapshotting the CopyOnWriteArrayList before assertion.

## Verification

./gradlew test --rerun — 121 tests, 0 failures. BotRegistryTest (12 tests) covers register/lookup/update/unregister/clear. PerceptionBroadcasterTest (9 tests) covers perception building, neighbourhood correctness, toroidal wrapping, cell-to-view conversion, and session sending.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew test --rerun` | 0 | ✅ pass | 10300ms |


## Deviations

Fixed pre-existing HundredBotIntegrationTest flake (CopyOnWriteArrayList race in assertion). Added handleAction stub in WorldWebSocketHandler (full resolution in T02).

## Known Issues

None.

## Files Created/Modified

- `src/main/java/com/paralife/engine/BotRegistry.java`
- `src/main/java/com/paralife/engine/PerceptionBroadcaster.java`
- `src/main/java/com/paralife/websocket/Messages.java`
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`
- `src/test/java/com/paralife/engine/BotRegistryTest.java`
- `src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java`


## Deviations
Fixed pre-existing HundredBotIntegrationTest flake (CopyOnWriteArrayList race in assertion). Added handleAction stub in WorldWebSocketHandler (full resolution in T02).

## Known Issues
None.
