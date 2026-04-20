---
phase: 15-protocol-transport-overhaul
plan: 03
subsystem: websocket
tags: [container, jetty, deflate, permessage-deflate, single-wiring-path, handshake, filter]
dependency-graph:
  requires:
    - "Spring Boot 3.4.4 + Jetty 12 BOM coordinates"
    - "WorldWebSocketHandler (unchanged) and SessionRegistry (unchanged)"
  provides:
    - "Jetty 12 embedded container with no Tomcat residue on runtime classpath"
    - "permessage-deflate; server_no_context_takeover advertised on /ws/world upgrade responses"
    - "D-33 server-side fail-fast: upgrade rejected (HTTP 400) when client offer lacks permessage-deflate; server_no_context_takeover"
    - "Runtime single-wiring-path invariant (WebSocketRouteAssertion) + three integration tests pinning it"
  affects:
    - "plan 15-09 (BotClient): must advertise permessage-deflate; server_no_context_takeover on its client upgrade request"
    - "plan 15-11 (test migration): will rewrite legacy WS tests to send the extension; expected-fail window documented in deferred-items.md"
tech-stack:
  added:
    - "spring-boot-starter-jetty (3.4.4 — brings Jetty 12.0.18 + jetty-ee10-websocket-jetty-server + jetty-websocket-core-*)"
  patterns:
    - "FilterRegistrationBean with Ordered.HIGHEST_PRECEDENCE for servlet-layer policy enforcement ahead of Jetty's WebSocketUpgradeFilter"
    - "Raw java.net.Socket + manual WS frame IO in tests — JDK HttpClient forbids Connection/Upgrade headers and JSR-356 clients do not expose extension negotiation"
    - "ApplicationReadyEvent listener asserting structural invariants (single-path registration) at startup"
key-files:
  created:
    - "src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java"
    - "src/main/java/com/paralife/websocket/WebSocketRouteAssertion.java"
    - "src/test/java/com/paralife/websocket/WebSocketDeflateHandshakeIntegrationTest.java"
    - "src/test/java/com/paralife/websocket/ServerRefusesUpgradeWithoutDeflateTest.java"
    - "src/test/java/com/paralife/websocket/WebSocketRouteAssertionTest.java"
    - ".planning/phases/15-protocol-transport-overhaul/deferred-items.md"
  modified:
    - "build.gradle.kts"
    - "src/main/java/com/paralife/websocket/WebSocketConfig.java"
decisions:
  - "Commit to Option B (Spring upgrade-strategy path) for the upgrade wiring. One registration at /ws/world lives in WebSocketConfig; no native Jetty addMapping anywhere. Review HIGH #3 dual-registration class of bug eliminated."
  - "Strict-client policy for permessage-deflate: the client MUST advertise server_no_context_takeover in its offer, not merely the base extension. Chosen after empirical discovery that Jetty 12's WebSocketUpgradeFilter reads headers from the underlying Jetty Request rather than the wrapped HttpServletRequest, so a server-side 'force' via request mutation does not work. Stricter client requirement achieves the same on-wire contract (Jetty echoes parameters verbatim)."
  - "Spring Framework 6.2's JettyRequestUpgradeStrategy customizer API (addWebSocketConfigurer(Consumer<Configurable>)) exposes only Configurable (timeouts/buffer sizes), NOT the upgrade creator. Extension enforcement therefore lives outside the strategy in a sibling servlet Filter."
  - "Legacy WebSocket tests (WebSocketIntegrationTest, HundredBotIntegrationTest, BotClientIntegrationTest, LoadTest, etc.) fail under the new policy — fix is owned by plan 15-11 per its explicit files_modified list; expected-fail window tracked in deferred-items.md."
metrics:
  duration: "~21 minutes (per per-task gradle timings)"
  completed: 2026-04-20
  tasks: 4
  files_changed: 7 (2 modified, 5 created) + 1 doc
requirements_addressed: [R22, R23, R24]
threat_refs: [T-15-02, T-15-05]
---

# Phase 15 Plan 03: Container + Deflate Overhaul — Summary

Jetty 12 replaces Tomcat as the embedded container, `permessage-deflate; server_no_context_takeover` is negotiated on every `/ws/world` upgrade, and the phase-1 review finding (HIGH #3: dual wiring path) is closed with a startup-time + behavioural invariant check.

## One-liner

Tomcat → Jetty 12 container swap plus a single-wiring-path `/ws/world` registration that enforces `permessage-deflate; server_no_context_takeover` via a servlet filter, with three integration tests pinning the negotiation, refusal, and round-trip contracts.

## Tasks Completed

| Task | Name                                                                 | Commit    | Files                                                                                                                                                                  |
| ---- | -------------------------------------------------------------------- | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | Swap Tomcat → Jetty 12 in `build.gradle.kts`                         | `456b62d` | `build.gradle.kts`                                                                                                                                                     |
| 2    | Wire deflate via single-path Jetty negotiation                       | `d922317` | `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java` (new), `src/main/java/com/paralife/websocket/WebSocketConfig.java`                                  |
| 3    | Handshake negotiation + refusal integration tests                    | `1dac58b` | `src/test/java/com/paralife/websocket/WebSocketDeflateHandshakeIntegrationTest.java` (new), `ServerRefusesUpgradeWithoutDeflateTest.java` (new), filter policy tighten |
| 4    | Startup + behavioural route-assertion for single handler path        | `e3b1dc2` | `src/main/java/com/paralife/websocket/WebSocketRouteAssertion.java` (new), `src/test/java/com/paralife/websocket/WebSocketRouteAssertionTest.java` (new)               |
| —    | Deferred items: pre-existing tests failing until 15-11 (documented)  | `23ff2e0` | `.planning/phases/15-protocol-transport-overhaul/deferred-items.md` (new)                                                                                              |

## Implementation Notes

### Spring Framework 6.2 `JettyRequestUpgradeStrategy` customization surface

The plan's Task 2 guidance hedged on which Spring API to use (`addWebSocketConfigurer` vs container-customizer vs fallback `WebServerFactoryCustomizer`). Empirical inspection of `spring-websocket-6.2.5.jar` via `javap` found exactly one public customizer method:

```
public void addWebSocketConfigurer(
    java.util.function.Consumer<org.eclipse.jetty.websocket.api.Configurable>);
```

`Configurable` exposes `idleTimeout`, input/output buffer sizes, max binary/text/frame sizes, auto-fragment, and max outgoing frames — it does **not** expose `setCreator` or any extension-negotiation hook. The upgrade creator Spring builds inside `JettyRequestUpgradeStrategy.upgrade()` is an `invokedynamic` lambda that never touches `Sec-WebSocket-Extensions`, so we cannot feed a customization through the strategy bean. The `@Bean JettyRequestUpgradeStrategy` in `JettyDeflateCustomizer` is therefore the default Jetty strategy; the deflate-specific policy lives in a sibling servlet `Filter`.

### Servlet-filter policy (no fallback `WebServerFactoryCustomizer` required)

`JettyDeflateCustomizer` ships two beans:

1. `JettyRequestUpgradeStrategy` — injected into `WebSocketConfig.registerWebSocketHandlers` via `DefaultHandshakeHandler`. This is the one and only wiring path for `/ws/world`.
2. `FilterRegistrationBean<Filter>` with `Ordered.HIGHEST_PRECEDENCE`, URL pattern `/*`, containing `DeflateEnforcementFilter`. The filter early-returns for non-upgrade traffic (checks `Upgrade: websocket`) so normal HTTP requests see zero policy overhead.

The filter:

- Rejects (HTTP 400) upgrade requests whose `Sec-WebSocket-Extensions` header lacks a `permessage-deflate` offer carrying `server_no_context_takeover` as a parameter.
- Allows passing requests through to Jetty's `WebSocketUpgradeFilter`, which negotiates the extension and echoes the requested parameters onto the response (Jetty 12 `PerMessageDeflateExtension.init` behaviour, verified by bytecode inspection: clients-echoed `server_no_context_takeover` sets `outgoingContextTakeover=false` and adds the parameter to `configNegotiated`).

No fallback `WebServerFactoryCustomizer<JettyServletWebServerFactory>` was needed.

### No native `addMapping` anywhere

Verified by the Task 2 acceptance criterion `grep -c "addMapping" src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java → 0`. The customizer never names a path; `WebSocketConfig` is the sole registrant. `WebSocketRouteAssertion` enumerates every `SimpleUrlHandlerMapping` bean at `ApplicationReadyEvent` and logs the resolved mapping: `[ws-route-assertion] Confirmed single handler path /ws/world — source=webSocketHandlerMapping:WebSocketHttpRequestHandler beans=worldWebSocketHandler=WorldWebSocketHandler`.

### Raw socket tests (not `java.net.http.HttpClient`)

The plan's Task 3 sketch used `HttpClient.newBuilder().header("Connection", "Upgrade")…`. The JDK HTTP client refuses to set the restricted `Connection` and `Upgrade` headers:

```
java.lang.IllegalArgumentException: restricted header name: "Connection"
```

Switched all three tests to `java.net.Socket` with manually-crafted HTTP/1.1 upgrade requests. `WebSocketRouteAssertionTest` extends this with a minimal RFC 6455 frame writer/reader so it can send a masked text frame through the upgraded connection without pulling in a dependency on Jetty's native WebSocket client.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Filter request-wrapping replaced with stricter client policy**

- **Found during:** Task 3 (negotiation test failed with the initial wrapper-based filter design).
- **Issue:** Jetty 12's `WebSocketUpgradeFilter.doFilter` unwraps the servlet request back to the underlying Jetty `Request` (`ServletContextRequest.getServletContextRequest(servletRequest)`) before reading `Sec-WebSocket-Extensions`. An `HttpServletRequestWrapper` is invisible to that code path, so the filter's attempt to append `server_no_context_takeover` did nothing and the response came back with only `permessage-deflate`.
- **Fix:** Removed `HeaderOverrideRequest` and the `ensureNoContextTakeover` mutator. Tightened the filter to require `server_no_context_takeover` in the client's offer — Jetty's negotiator echoes parameters verbatim, so the on-wire contract is unchanged. Plan 15-09 (BotClient) will satisfy this naturally per D-33 client-side enforcement.
- **Files modified:** `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java` (removed nested `HeaderOverrideRequest` class, removed `ensureNoContextTakeover`, renamed `containsDeflate` → `offersDeflateWithNoContextTakeover`).
- **Commit:** `1dac58b` (bundled with the tests since the fix is what made them green).

**2. [Rule 3 - Blocking] Test upgrade path switched from `HttpClient` to raw `Socket`**

- **Found during:** Task 3 (first test run).
- **Issue:** `java.net.http.HttpClient.Builder.header("Connection", "Upgrade")` throws `IllegalArgumentException: restricted header name: "Connection"`. Same for `Upgrade`. The JDK's HTTP client has an allow-list that excludes the headers a raw WebSocket upgrade requires.
- **Fix:** Replaced `HttpClient` usage with `java.net.Socket` + manually-crafted HTTP/1.1 request lines. Added a shared helper `sendRawUpgrade(port, rawRequest)` in `WebSocketDeflateHandshakeIntegrationTest` that `ServerRefusesUpgradeWithoutDeflateTest` reuses.
- **Files modified:** `WebSocketDeflateHandshakeIntegrationTest.java`, `ServerRefusesUpgradeWithoutDeflateTest.java` (both new).
- **Commit:** `1dac58b`.

**3. [Rule 3 - Blocking] Route-assertion test: JSR-356 client → raw socket + manual WS frames**

- **Found during:** Task 4 (first test run with `StandardWebSocketClient`).
- **Issue:** `StandardWebSocketClient` uses the JSR-356 Jakarta container, which doesn't route `Sec-WebSocket-Extensions` from `WebSocketHttpHeaders` into the actual handshake; the deflate filter rejected the test's upgrade with HTTP 400. Adding `jetty-ee10-websocket-jetty-client` to testCompile was an option but would have enlarged the Gradle dependency set for one test.
- **Fix:** Replaced the Spring `WebSocketClient` usage with raw `Socket`. Inlined a minimal RFC 6455 frame writer/reader (mask on write, unmask on read, payload-length 7/16/64-bit, RSV1-aware) in the test class. The probe sends `GARBAGE` as an uncompressed text frame; `WorldWebSocketHandler.handleTextMessage` Jackson-fails and sends back `Error(INVALID_MESSAGE)`, which the test reads and asserts.
- **Files modified:** `WebSocketRouteAssertionTest.java` (new, raw socket path).
- **Commit:** `e3b1dc2`.

### Deferred (out-of-scope)

**4. 8 pre-existing integration tests fail under the new deflate enforcement**

- **Found during:** full `./gradlew test` run after Task 4.
- **Root cause:** These tests (`WebSocketIntegrationTest`, `HundredBotIntegrationTest`, `BotClientIntegrationTest`, `EnvironmentFullStackSmokeTest`, `LoadTest`, `MetabolismIntegrationTest`, `PerceptionActionIntegrationTest`, `PopulationDynamicsTest`) either (a) use `StandardWebSocketClient` without negotiating the deflate extension, or (b) exercise the old JSON protocol that plan 15-09+15-11 replace.
- **Disposition:** Deferred to plan **15-11 (Test Migration)**, which already lists these files in its `files_modified` and explicitly accepts responsibility ("Existing integration tests … pass end-to-end under the new wire protocol"). Logged in `.planning/phases/15-protocol-transport-overhaul/deferred-items.md`.
- **Scope justification:** Fixing these in 15-03 would duplicate 15-11's codec migration work and couple wave 1 to waves 2-7. The plan's verification criterion only requires the three new tests to pass, which they do.

## Authentication Gates

None occurred.

## Self-Check

Validation of claims (per self_check protocol):

### Created files exist

- FOUND: `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java`
- FOUND: `src/main/java/com/paralife/websocket/WebSocketRouteAssertion.java`
- FOUND: `src/test/java/com/paralife/websocket/WebSocketDeflateHandshakeIntegrationTest.java`
- FOUND: `src/test/java/com/paralife/websocket/ServerRefusesUpgradeWithoutDeflateTest.java`
- FOUND: `src/test/java/com/paralife/websocket/WebSocketRouteAssertionTest.java`
- FOUND: `.planning/phases/15-protocol-transport-overhaul/deferred-items.md`

### Commits exist (worktree branch `worktree-agent-ae41b98e`)

- FOUND: `456b62d` (Task 1: Gradle swap)
- FOUND: `d922317` (Task 2: customizer + config)
- FOUND: `1dac58b` (Task 3: handshake tests + policy tighten)
- FOUND: `e3b1dc2` (Task 4: route assertion + probe test)
- FOUND: `23ff2e0` (deferred-items doc)

### Acceptance criteria (plan verification block)

- `./gradlew build -x test` — PASSED
- `./gradlew test --tests '…DeflateHandshakeIntegrationTest' --tests '…ServerRefusesUpgradeWithoutDeflateTest' --tests '…WebSocketRouteAssertionTest'` — PASSED (all 3 green)
- Runtime classpath has no Tomcat, has Jetty 12 (12.0.18 via BOM)
- Startup log contains `[ws-route-assertion] Confirmed single handler path /ws/world` (verified from test-results XML)
- No `JettyWorldWebSocketAdapter.java` exists

## Self-Check: PASSED
