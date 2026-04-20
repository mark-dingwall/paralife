---
phase: 15
slug: protocol-transport-overhaul
status: verified
threats_open: 0
asvs_level: 1
created: 2026-04-20
---

# Phase 15 — Security

Per-phase security contract: threat register, accepted risks, and audit trail for the Protocol & Transport Overhaul.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| untrusted client → `/ws/world` upgrade | Extension negotiation is the server's only chance to reject the connection before any frames flow. | WebSocket handshake headers (`Sec-WebSocket-Extensions`) |
| untrusted client → inflated frame | `permessage-deflate; server_no_context_takeover` bounds statefulness; Jetty `maxTextMessageSize` bounds inflated payload size. | Post-inflate text frame bytes |
| untrusted wire → `PerceptionCodec.decode` | Codec is the authoritative V5 (Input Validation) layer. Record canonical constructors, entry/event caps, and LL(1) parser reject malformed or oversized input. | Raw text frames (`T|`, `a|`, `r|`, `p|`) |
| server world state → outbound `T` frame | Broadcaster + codec are the authoritative egress filter. Kind-code mapping must never emit entity ids, bonded-secondary types, or out-of-vision data. | Snapshot / Tick / Event frames |
| /actuator/metrics → operator | Narrow three-endpoint allowlist; metrics are aggregate-only. | Aggregate meter samples (scalar session count, byte-length distribution) |
| classpath `/rocks/*.png` → `RockGenerator` | Build-time bundled resources; verified at `@PostConstruct`, startup fails fast if tampered or missing. | Image bytes → world terrain |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-15-00 | Tampering | planning artifacts | accept | Git-tracked; no runtime surface. | closed |
| T-15-01 | DoS | `PerceptionCodec.decode` | mitigate | Single-pass LL(1) parser; `MAX_S_ENTRIES=256` (L29), `MAX_V_ENTRIES=32` (L35) with cap enforcement at L443/L639; var-base64 11-char cap L860/872/884; `Frame` record canonical-ctor range guards (L62–70); handler wraps `CodecException` as `E\|400` at L107–110. Pinned by `PerceptionCodecErrorTest.largeInputRejectedQuickly` (500 ms on 100 KB bomb), `boundedEntriesRejected`, `boundedEventsRejected`, `WorldWebSocketHandlerTest.malformedFrameProducesError400`. | closed |
| T-15-02 | Spoofing / extension downgrade | WebSocket upgrade (server + client) | mitigate | **Server:** `JettyDeflateCustomizer.DeflateEnforcementFilter` L106–168 returns HTTP 400 when `permessage-deflate; server_no_context_takeover` is absent. Pinned by `ServerRefusesUpgradeWithoutDeflateTest`. **Client:** `BotClient.connect` L120–126 inspects `Sec-WebSocket-Extensions` response header, closes 1002 + throws `IllegalStateException` when missing. Pinned by `BotClientClosesOnMissingServerDeflateTest`. | closed |
| T-15-03 | Information Disclosure | codec encode + TickBroadcaster + PerceptionBroadcaster | mitigate | `CellEntry` record (L11–12) carries no id field — codec cannot emit an id. `TickBroadcaster.entityStateOf` L506–518 uses ids only for internal lookup; id never crosses the wire. Vision-scoped OVERCROWDED preserved verbatim at `TickBroadcaster.envStateFor` L538: `byte cellStatus = (byte) ((cached & ~BIT_OVERCROWDED) \| perBotOvercrowdedBit);`. Pinned by `ZeroTrustFilteringTest.encodedFrameCarriesNoEntityIds`, `bondedNeighbourEmitsOnlyPrimaryKindCode`, `selfCellIsNeverEmittedInSBlock`. | closed |
| T-15-04 | DoS (respawn storm) | `handleRegister` | mitigate | `WorldWebSocketHandler` L65 `MAX_RESPAWNS_PER_SESSION = 5`; L162–165 emits `E\|429` on exceedance. Client `BotClient.onError` L262–268 disconnects on 429 rather than looping. Pinned by `WorldWebSocketHandlerTest.respawnCapEnforced` (L73). | closed |
| T-15-05 | DoS (zip-bomb / oversized inflated frame) | Jetty 12 deflate decoder | mitigate (soft) | Relies on Jetty default `WebSocketPolicy.maxTextMessageSize` (64 KiB). Adequate for ~1 KiB tick-frame budget at 100-bot scale. No explicit override; no automated pin test — see follow-up note #1. | closed |
| T-15-RG-01 | Tampering | classpath `/rocks/*.png` | accept | Build-bundled; git-tracked. `RockGenerator.verifyTextures` L62–80 throws `IllegalStateException` at `@PostConstruct` if any resource is missing or fails to decode. Pinned by `RockGeneratorMissingPngTest.missingPngFailsFastAtStartup` + `allPresentTexturesInitializeCleanly`. | closed |
| T-15-MX-01 | Information Disclosure | `/actuator/metrics` | accept | `application.yml` L15 `management.endpoints.web.exposure.include: health,info,metrics` — no `env`/`configprops`/`beans`/`loggers`. Two Micrometer meters registered: `paralife.ws.active.sessions` (scalar gauge) and `paralife.ws.tick.frame.bytes` (DistributionSummary) — aggregate-only; no entity/session ids. Pinned by `MetricsEndpointIntegrationTest`. | closed |
| T-15-TM-01 | Tampering | test coverage | accept | Plan 15-11 migrated legacy Messages-era tests without `@Disabled` escape hatches. Full-suite green gate enforced at phase close. | closed |
| T-15-TM-02 | DoS | encode+deflate CPU budget | mitigate (soft) | `EncodeDeflatePerformanceGateTest` runs 100 bots with `server_no_context_takeover=true`. Preferred assertion: p99 `paralife.tick.drift.millis` ≤ 2× target. Current fallback: connection-survival proxy (≥ 90/100 bots still connected after 50-tick window) because `TickEngine` does not yet publish the drift meter — see follow-up note #2. | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-15-00 | T-15-00 | Planning artifacts are documentation-only; no runtime surface. Git tracks divergence. | Phase 15 plan-checker | 2026-04-20 |
| AR-15-RG | T-15-RG-01 | Classpath PNG tampering triggers `@PostConstruct` IllegalStateException — startup fails fast, no silent fallback. | Phase 15 plan 15-04 | 2026-04-20 |
| AR-15-MX | T-15-MX-01 | `/actuator/metrics` allowlist `health,info,metrics` exposes only aggregate numbers (scalar gauge + byte-length distribution). No PII, no entity/session ids. | Phase 15 plan 15-10 | 2026-04-20 |
| AR-15-TM | T-15-TM-01 | Test migration preserved intent; no `@Disabled` hidden. Full green gate held. | Phase 15 plan 15-11 | 2026-04-20 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-04-20 | 10 | 10 | 0 | gsd-security-auditor (State B — initial creation) |

---

## Follow-Up Notes (non-blocking)

1. **T-15-05 pin test** — add a `maxInflatedFrameRejected` test that feeds a server-inflated 100 KB frame and asserts Jetty closes the session with the too-large code. Guards against a Jetty default change.
2. **T-15-TM-02 drift metric** — expose `paralife.tick.drift.millis` DistributionSummary from `TickEngine`; the perf gate already has the preferred-assertion branch ready and would switch automatically.
3. **T-15-02 attribution** — threat register text in plans 15-03/15-09 cites `jettyRequestUpgradeStrategy()` as the enforcement point; the actual refusal lives in `DeflateEnforcementFilter`. Pattern is correct, attribution is off — worth updating on the next edit.

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-04-20
