# Deferred Items — Phase 15

Out-of-scope discoveries logged by plan executors per GSD scope-boundary rule.

## Logged 2026-04-20 (15-04 executor)

### 15-03 permessage-deflate commit (`546b211`) regresses ~17 integration tests

**Observed:** Running `./gradlew test` after commit `546b211 feat(15-03): wire
permessage-deflate via single-path Jetty negotiation` fails the following
integration-level tests that passed at `672c600` (last 15-04 commit, pre-
permessage-deflate):

| Test | Failure mode |
|------|--------------|
| `WebSocketDeflateHandshakeIntegrationTest.serverNegotiatesPermessageDeflateWithNoContextTakeover` | UpgradeException |
| `BotClientIntegrationTest.nineBotsConnectAndMakeDecisions` | AssertionFailedError |
| `EnvironmentFullStackSmokeTest.perceptionFrameCarriesNonZeroStatusWithin60Ticks` | UpgradeException |
| `LoadTest.hundredBotsNoCorruption` | AssertionError |
| `MetabolismIntegrationTest.allTypesSurviveWithMetabolism` | AssertionError |
| `PerceptionActionIntegrationTest.*` (6 methods) | UpgradeException |
| `PopulationDynamicsTest.allThreeTypesSurvive500Ticks` | AssertionError |
| `HundredBotIntegrationTest.hundredBotsConnectAndReceiveTicks` | AssertionFailedError |
| `WebSocketIntegrationTest.*` (4 methods) | UpgradeException |

**Verified:**
- At `f96c505` (Jetty swap only, no deflate): `MetabolismIntegrationTest` PASSES.
- At `672c600` (post-15-04, pre-deflate): `MetabolismIntegrationTest` PASSES.
- At `546b211` (post-deflate): `MetabolismIntegrationTest` FAILS.

**Plan 15-04 scope:** Rock generation — entirely server-local, no wire
interaction. The 15-04 `RockConfigTest`, `RockGeneratorTest`, and
`RockGeneratorMissingPngTest` all PASS at every commit from `b81e064` onward.

**Disposition:** Deferred to 15-03 executor / 15-03 verifier. Not a 15-04 bug.
