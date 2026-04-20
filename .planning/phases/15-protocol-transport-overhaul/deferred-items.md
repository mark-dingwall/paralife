# Phase 15 Deferred Items

Items discovered during plan execution that are out of scope for the current plan but must be tracked for later resolution.

## Pre-existing integration tests failing after 15-03 deflate enforcement (expected, owned by 15-11)

**Discovered during:** 15-03 Task 3/4 — after the `DeflateEnforcementFilter` landed, pre-Phase-15 integration tests that use `StandardWebSocketClient` without configuring `permessage-deflate; server_no_context_takeover` now fail at the upgrade step with HTTP 400. Independently confirmed by 15-04's bisection (commit `546b211` is the regression point; `f96c505` pre-deflate is green).

**Affected tests (union of 15-03 inventory and 15-04 bisection):**

| Test class | Failure mode |
|------------|--------------|
| `com.paralife.websocket.WebSocketIntegrationTest.*` (4 methods) | UpgradeException |
| `com.paralife.websocket.HundredBotIntegrationTest.hundredBotsConnectAndReceiveTicks` | AssertionFailedError |
| `com.paralife.bot.BotClientIntegrationTest.nineBotsConnectAndMakeDecisions` | AssertionFailedError |
| `com.paralife.engine.EnvironmentFullStackSmokeTest.perceptionFrameCarriesNonZeroStatusWithin60Ticks` | UpgradeException |
| `com.paralife.engine.LoadTest.hundredBotsNoCorruption` | AssertionError |
| `com.paralife.engine.MetabolismIntegrationTest.allTypesSurviveWithMetabolism` | AssertionError |
| `com.paralife.engine.PerceptionActionIntegrationTest.*` (6 methods) | UpgradeException |
| `com.paralife.engine.PopulationDynamicsTest.allThreeTypesSurvive500Ticks` | AssertionError |

Also: `WebSocketDeflateHandshakeIntegrationTest.serverNegotiatesPermessageDeflateWithNoContextTakeover` failed at the interim 15-04-bisection point but passes after 15-03's full commit set lands.

**Verified pass-points (15-04 bisection):**
- `f96c505` (Jetty swap only, no deflate): `MetabolismIntegrationTest` PASSES.
- `672c600` (post-15-04, pre-deflate): `MetabolismIntegrationTest` PASSES.
- `546b211` (post-deflate): `MetabolismIntegrationTest` FAILS.

**Root cause:** These tests either
- (a) open a WebSocket session via Spring's `StandardWebSocketClient` (JSR-356), which does not route `Sec-WebSocket-Extensions` through its Jakarta client container; or
- (b) exercise the old JSON-per-tick protocol which is being replaced by the compact codec (D-01 big-bang).

**Owner plan:** **15-11** (Test Migration) already lists these files in `files_modified` and will rewrite them against the new codec + with proper extension negotiation. The 15-11 plan's acceptance criteria include "Existing integration tests (LoadTest, PopulationDynamicsTest, HundredBotIntegrationTest) pass end-to-end under the new wire protocol."

**Why not fix in 15-03:** The plan's scope is server-side container swap + deflate wiring + three targeted integration tests. Rewriting 8 unrelated test classes with codec frames belongs to 15-11 where the codec is fully wired through `TickBroadcaster` and `BotClient`. Forcing those fixes now would duplicate 15-11's work and couple waves 1→7 prematurely.

**Rule classification:** Scope boundary — the test regressions are directly caused by 15-03's deflate enforcement (technically a Rule 3 blocker for the full `./gradlew test` target), but the plan's own verification only requires the three new tests to pass. Per project convention, big-bang protocol changes accept a "yellow" test suite across intermediate waves.

**Expected green window:** After 15-11 merges. Until then, use targeted `--tests` invocations when running Gradle locally.

**Disposition:** Deferred to 15-11 (test migration plan). Not a 15-03 or 15-04 bug.
