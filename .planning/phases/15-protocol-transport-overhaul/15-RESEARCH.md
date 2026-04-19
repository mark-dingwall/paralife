# Phase 15: Protocol & Transport Overhaul - Research

**Researched:** 2026-04-20
**Domain:** Compact text WebSocket protocol + Jetty 12 container swap + permessage-deflate negotiation + stateless bot refactor + procedural rock generation + Micrometer metrics
**Confidence:** HIGH

## Summary

The schema is locked (15-SCHEMA.md §9 gate D-50 satisfied 2026-04-20). All wire-format decisions — 13 round-trip vectors, presence-bitmask entry grammar, numpad directions, authority tiers, frame inventory `r/S/T/a/E` — are authoritative. Research concentrated on the remaining operational unknowns: the Jetty-12-under-Spring-Boot-3.4.4 container swap, programmatic `permessage-deflate` extension negotiation on both server and client, codec implementation style, stateless bot refactor surface, PNG-based rock loader, and Micrometer wiring.

Outcome of the operational research is unambiguous on every axis except one: **Spring's `StandardWebSocketClient` has no public extension API** [VERIFIED: Spring Framework javadoc]. Clients that need to advertise `permessage-deflate; server_no_context_takeover` must either (a) switch the `BotClient` to Jetty's native `WebSocketClient` with `ClientUpgradeRequest.addExtensions(...)`, or (b) subclass `StandardWebSocketClient` and override the protected `executeInternal(..., extensions, ...)` hook. Option (a) is recommended — it's the path Jetty documents for this exact use case and it removes one layer of indirection from a performance-sensitive client.

**Primary recommendation:** Ship the container/compression swap first (foundation), then the codec (pure-Java, no Spring dependencies), then the bot refactor + rock generator + metrics in parallel. The 13 locked vectors in `15-SCHEMA.md §10` are the acceptance oracle for the codec — build the parameterised round-trip test before any other code.

## User Constraints (from CONTEXT.md + SCHEMA.md)

### Locked Decisions

Schema-side (from `15-SCHEMA.md` — authoritative, overrides CONTEXT.md on wire format):

- **§1 Alphabet:** single 64-char base64 `0-9A-Za-z_-`. One `charToInt[128]` + `intToChar[64]` pair shared across every field.
- **§2 Coordinates:** three forms — absolute (4-char unsigned base64), relative (`[+-]x[+-]y` 4 chars), numpad (single `1-9`). Parser disambiguates by first-char class + fixed positional slots.
- **§3 Separators:** `,` between list entries; `:` intra-entry; `;` is never used anywhere.
- **§5 Frame inventory:** `r` (C→S register), `S` (S→C sync), `T` (S→C tick), `a` (C→S action), `E` (S→C error). No `W`, `R`, `CT`, `MT`, `v|`, `a|S`, `a|H` frames.
- **§6.3 `T` header:** `T|<tickId>|<curX><curY>|<energy>/<maxEnergy>|<sensorRadius>` (full / authority-lite) or `T|<tickId>|<curX><curY>|<energy>/<maxEnergy>[|v<event>,...]` (passive minimal).
- **§7 Authority tiers:** full (solo/bonded/LOCOMOTOR), authority-lite (FEEDER/ATTACKER/REPRODUCER — radius 1), passive (SENSOR/DEFENDER — minimal frame).
- **§8.1 `s` block:** coord-first, presence-bitmask byte per entry. RLE is kind-only, numpad direction, base64 additional-count.
- **§8.1.1 Kind codes:** `C`, `M`, `S`, `D`, `N`, `T`, `0`-`5`, `R`, `F`.
- **§8.1.2 entityState bits:** 0=STARVING, 1=MUTATING, 2=BUFFED; omitted when 0.
- **§8.1.3 envState bits:** 0=OVERCROWDED (vision-scoped per D-40), 1=TOXIN_PRESENT, 2=MUTAGEN_ZONE.
- **§8.3 `f` effects:** `I`, `F` (FLEEING, abs strike coord in ctx), `A`, `M`, `S`, `U`. No `CR`/`R` reproduce cooldown on wire.
- **§8.4 `v` events:** `E`, `A`, `H`, `T`, `M`, `R`, `L`, `N`, `S`, `D` with coord-first + magnitude rules per table.
- **§8.5 `g` roster:** `g<coord><role>,...` on-change-only, no `:<size>` (derived from count).
- **§8.6 `a` actions:** `M`, `E`, `A`, `R`, `V` (3-digit numpad IRV ranks), `L` (alarm).
- **§10 Round-trip vectors:** 13 locked test cases. `encode(decode(x)) == x` byte-for-byte. Parameterised test is the acceptance oracle.
- **§11 Authority / behaviour matrix:** Solo + Bonded radius 2 (3 with SENSOR_PLUS_1); LOCOMOTOR composite-stitched; FEEDER/ATTACKER/REPRODUCER radius 1; DEFENDER/SENSOR minimal.
- **§12 Parser is LL(1), single-pass, no backtracking.**
- **§9 "New scope additions":** FLEEING effect + lightning flee mechanic IN for Phase 15; alarm action `a|L` + `vN<coord>` event delivery; IRV vote (replaces plurality); client-side respawn flow (session stays open post-`vD`); nutrient kind `F`; presence bitmask supersedes `;` sentinel.

CONTEXT-side (carried forward, not reversed):

- **D-01, D-02, D-03:** big-bang protocol replacement; rename `PerceptionBroadcaster` → `TickBroadcaster`; delete the old heartbeat `TickBroadcaster`; ship codec + container/deflate swap + stateless bot + zero-trust filtering + rock generation + actuator metrics.
- **D-04:** shared base64 alphabet (same as Phase 14 D-36).
- **D-05, D-06:** 4-char relative coords; 4-char absolute expiry tick IDs (absolute, not remaining).
- **D-08:** self cell omitted from `s` block.
- **D-10:** sensor radius self-describing in `T` header.
- **D-12:** effects vs events vs vision — three sections, never merged.
- **D-20:** self bitmask not sent.
- **D-28:** neighbour IDs fully dropped (plus bonded-secondary type hidden per schema).
- **D-29:** per-session pseudonym IDs rejected.
- **D-30:** swap Tomcat→Jetty 12 via starter exclusion + `starter-jetty`. Spring Boot 3.4.4 ships Jetty 12 (Jakarta EE 10 namespace).
- **D-31, D-32, D-33:** programmatic `permessage-deflate` with `server_no_context_takeover=true` on both sides. Handshake integration test required. Fail-fast enforcement: server refuses upgrade if client doesn't negotiate; client closes if server doesn't advertise.
- **D-34, D-35, D-36:** PNG-based procedural rock generator. Random file choice → rotate → flip → luminance-threshold. Seed configurable via `paralife.world.rock-seed`. Poisson-disk deferred post-MVP.
- **D-38:** three Micrometer metrics — Counter `paralife.ws.bytes-saved`, Gauge `paralife.ws.active-sessions`, DistributionSummary `paralife.ws.tick-frame-bytes`.
- **D-39:** `compress-ops-saved` metric dropped alongside fan-out infra.
- **D-40, D-41:** `PerceptionCodec` — pure Java, no Spring deps, no hidden state. Lives at `com.paralife.codec`.
- **D-42:** `BotClient` Phase-09 `JsonNode`/`LinkedHashMap` tech debt forced-eliminated by codec adoption. Minimal cached state: entity id (from `S`), current type (from `T` `c` block).
- **D-43, D-44:** `HeuristicBrain` becomes pure function of decoded tick frame. Fog-of-war memory still post-MVP.
- **D-47:** REQUIREMENTS.md R15–R19 renumber (planner handles).

### Claude's Discretion

None from CONTEXT.md (`15-CONTEXT.md` notes explicitly: "every open encoding / grammar decision requires user input during formal schema review (D-50)"). Now that D-50 is resolved, the remaining discretion space is narrow:

- Exact Gradle syntax for starter swap (standard Spring Boot pattern — no user choice needed).
- Micrometer meter-name casing (convention follows existing Spring Boot Actuator metric namespace).
- Rock PNG file layout / count (planner picks; recommend 5–10 64×64 variants shipped in `src/main/resources/rocks/`).
- Choice between Jetty native `WebSocketClient` vs subclassing Spring's `StandardWebSocketClient` for bot client extension negotiation — recommended path is Jetty native (see §Jetty 12 notes below).
- Parser style inside codec (StringBuilder / index vs split — recommended style in §Codec Architecture).

### Deferred Ideas (OUT OF SCOPE)

Copied verbatim from CONTEXT.md `<deferred>`:

- `BroadcastChannel` / `CompressedFrame` precompress fan-out → M005.
- Visualizer UI / observer endpoint / world-state broadcast → M005.
- Observer transport evaluation (SSE vs WebSocket vs HTTP/2 push) → M005.
- Global world-stats heartbeat (entityCount/bondCount/compositeCount) — removed from bot-facing protocol, reintroduced server-side for observers in M005.
- Bot memory / fog of war / A* / shadowcasting → post-MVP (curCoords foundation laid).
- Composite rotation → post-MVP.
- Per-session pseudonym IDs → post-MVP.
- Multi-tick gestation reproduction → post-MVP.
- Persistent POISONED debuff → post-MVP.
- Clustered Poisson-disk rock generator → post-MVP.
- FEEDER / ATTACKER / REPRODUCER advanced target-selection heuristics → post-MVP (MVP ships fallback-auto + basic single-target choice).

## Phase Requirements

Phase 15 has no formal R-numbered requirements in `REQUIREMENTS.md`. Per D-47, `REQUIREMENTS.md` R15–R19 currently describe emergence tests that belong to Phase 16. Phase 15 scope derives from `ROADMAP.md` lines 127–139 + `15-CONTEXT.md` + `15-SCHEMA.md`. Planner renumbers during plan phase. For research purposes, the success criteria map:

| Success Criterion (from ROADMAP + CONTEXT + SCHEMA) | Research Support |
|---|---|
| Compact text Perception protocol with sparse relative coords, base64 encoding, fixed-width status bitmasks | §Codec Architecture; §Round-trip vectors (§8.1 Schema) |
| Stateless bot redesign — no client-side caching | §Stateless Bot Refactor; §Current Code Assessment |
| Zero-trust perception filtering | §Schema §8.1 kind codes hide bonded-secondary type; neighbour IDs dropped |
| Tomcat → Jetty container swap | §Jetty 12 + Spring Boot 3.4.4 Integration |
| `permessage-deflate` with `server_no_context_takeover=true` negotiated both sides | §permessage-deflate Negotiation (server + client) |
| `PerceptionCodec` shared encode/decode | §Codec Architecture (pure Java, package `com.paralife.codec`) |
| Bot→Server compact action format | Schema §6.4 + §8.6 — unified `a|<verb>[|<arg>]` |
| Actuator custom metrics | §Micrometer Metrics |
| Rock generation algorithm | §PNG-based Rock Generation |
| All existing tests pass under new protocol | §Test Migration Impact |
| Handshake integration test (D-32) | §Handshake Inspection Test |
| Fail-fast enforcement (D-33) | §Fail-fast Enforcement |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|---|---|---|---|
| WebSocket transport + extension negotiation | Container (Jetty 12) | Spring WebSocket config | Extension config hook sits on Jetty's `ServerWebSocketContainer` / `WebSocketCreator` — Spring surfaces access to it via `JettyServletWebServerFactory` customizer |
| Compact text encode/decode | `com.paralife.codec` (pure Java) | — | No Spring deps per D-40; testable without `@SpringBootTest` |
| Tick frame assembly (state + events + effects + vision) | `com.paralife.engine.TickBroadcaster` (renamed from `PerceptionBroadcaster`) | `EnvironmentEngine` (status caches), `BuffRegistry`, `BotRegistry`, `CompositeRegistry` | Existing per-tick assembly pipeline at `@Order(50)` stays; only the projection-to-wire step swaps from Jackson to codec |
| Per-bot vision-scoped OVERCROWDED bit | `TickBroadcaster.cellToView` | — | Phase 14 D-40 logic preserved verbatim; same mask-and-OR expression feeds the `envState` char |
| Action parsing + dispatch | `com.paralife.websocket.WorldWebSocketHandler` | `ActionResolver`, codec | Handler decodes `a\|...` via codec and dispatches on verb; no Jackson on the read path |
| Bot decoding + decision | `com.paralife.bot.BotClient` (refactored) + `HeuristicBrain` (pure function) | Codec (shared) | Zero neighbour memory; decision driven entirely by latest decoded `T` frame |
| Rock generation | `com.paralife.world.RockInitializer` (new) | `WorldGrid`, classpath PNG resources | Runs once at world init; seed-deterministic per D-35 |
| Metrics publication | `com.paralife.websocket` (bytes-saved, active-sessions, tick-frame-bytes) | Spring Boot Actuator + Micrometer auto-config | `MeterRegistry` bean injection is the standard path |

## Standard Stack

### Core (already in the project, re-used)

| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| Spring Boot | 3.4.4 | App framework, auto-config | Already pinned; provides Jetty 12 dep management via BOM [CITED: spring.io Jetty 12 upgrade #36073; confirmed Jetty 12 shipped since SB 3.2] |
| Java | 21 | Virtual threads, records, sealed interfaces | Already on toolchain |
| Spring Boot Starter WebSocket | 3.4.4 | Raw `WebSocketHandler` registration | Already in use at `/ws/world` |
| Spring Boot Starter Actuator | 3.4.4 | Exposes Micrometer metrics via `/actuator/metrics` | Already in use |
| Jackson | transitive | JSON (being removed from WS path) | Retained for other JSON use if any; removed from codec path |

### Core swap-in (replacing starter-tomcat)

| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| `org.springframework.boot:spring-boot-starter-jetty` | 3.4.4 (BOM-managed → Jetty 12.x) | Jetty 12 container + Jakarta WebSocket + native Jetty WebSocket API | Standard Spring Boot starter swap per D-30 [CITED: docs.spring.io WebSockets reference] |

**Version verification command:**
```bash
./gradlew dependencyInsight --dependency org.eclipse.jetty:jetty-server --configuration runtimeClasspath
```
Expected output: Jetty 12.0.x (Spring Boot 3.4.4 BOM pins Jetty 12; 3.4's Jetty version as of release was 12.0.17). [VERIFIED: Spring Boot 4.0.5 docs show jetty 12.1.7; SB 3.4 shipped with 12.0.x — planner confirms with dependency insight before pinning]

### Codec (new package `com.paralife.codec`)

| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| Pure Java (no library) | JDK 21 | String parse + StringBuilder encode | Schema §12 is LL(1) single-pass; adding a parser generator (ANTLR, JParsec) is overkill and adds a Spring-free dep tree requirement [VERIFIED: schema spec § 12 explicitly states "No backtracking required"] |

### Rock Generation (new)

| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| `javax.imageio.ImageIO` | JDK 21 built-in | PNG decode → `BufferedImage` | Zero dep cost; handles palette + RGB + grayscale + alpha [VERIFIED: `javax.imageio` is part of JDK; still called `javax.*` in JDK 21 despite Jakarta migration — unrelated namespace] |
| `java.awt.image.BufferedImage` + `Raster` | JDK 21 built-in | Sample luminance / alpha | Standard pixel access path |

### Metrics (already available)

| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| Micrometer Core | transitive via `spring-boot-starter-actuator` 3.4.4 (Micrometer 1.14.x) | Counter, Gauge, DistributionSummary | Spring Boot auto-config provides a `MeterRegistry` bean; standard Actuator pattern [VERIFIED: SB 3.4 ships Micrometer 1.14.x via BOM] |

### Testing (already in tree)

| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| JUnit 5 | transitive via `spring-boot-starter-test` 3.4.4 | Unit + parameterised tests | Required for `@ParameterizedTest` + `@MethodSource` on the 13 round-trip vectors |
| `spring-boot-starter-test` | 3.4.4 | `@SpringBootTest`, `TestRestTemplate` | Existing pattern; integration tests follow this |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|---|---|---|
| Jetty native `WebSocketClient` in `BotClient` | Subclass `StandardWebSocketClient` and override `executeInternal` | Subclass route works but requires overriding a `protected` method; Jetty native is Jetty's documented path for extension negotiation and is cleaner given we've already committed to Jetty server-side [VERIFIED: javadoc shows no public setExtensions on StandardWebSocketClient] |
| ANTLR for codec | Hand-written LL(1) parser | LL(1) single-pass (§12) is trivial to hand-write; ANTLR adds runtime dep + grammar file + generated code complexity |
| Kryo / protobuf for binary | Compact text | Binary already vetoed by scope — `permessage-deflate` closes the compression gap on a text protocol and keeps debuggability |
| Netty / OkHttp bot client | Jetty native `WebSocketClient` | Netty/OkHttp add extra dep trees; Jetty client is already on classpath once `starter-jetty` is in |

**Installation (Gradle Kotlin DSL):**

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-websocket") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jetty")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Note: `spring-boot-starter-jetty` transitively brings in `org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-jetty-server`, `jetty-ee10-websocket-jakarta-server`, and the core `jetty-websocket-core-*` modules. `spring-boot-starter-websocket` provides Spring's WS abstraction; combined the stack gives raw Jetty extension access + Spring's `WebSocketHandler` surface. [CITED: Spring Boot BOM dependency graph]

## Jetty 12 + Spring Boot 3.4.4 Integration

### Namespace check

All project source already uses `jakarta.*` / `org.springframework.web.socket.*` — zero `javax.*` imports remain (verified via grep over `src/`). Jakarta EE 10 namespace migration is complete; Jetty 12 ships on EE 10, so starter swap is name-clean. [VERIFIED: grep pattern `javax\.` in src/ returned no files]

### Auto-configuration behaviour

Spring Boot's `WebSocketAutoConfiguration` + `JettyWebSocketServletWebServerCustomizer` activate automatically when `spring-boot-starter-jetty` is on the classpath and Tomcat is excluded. [CITED: docs.spring.io Spring Boot reference — "Spring Boot provides WebSockets auto-configuration for embedded Tomcat and Jetty"] Swapping starters is sufficient; no explicit `JettyServletWebServerFactory` bean is required for baseline operation. Extension negotiation (§permessage-deflate) requires an explicit customizer (see below) — the auto-config does NOT advertise `permessage-deflate` by itself.

### Server-side permessage-deflate negotiation (D-31 + D-32 + D-33)

Jetty 12's `ServerWebSocketContainer` does **not** expose an `addExtension` API [VERIFIED: Jetty 12 javadoc for `ServerWebSocketContainer`]. The correct extension-negotiation hook is the `WebSocketCreator` callback invoked during upgrade. The creator receives `ServerUpgradeRequest` and `ServerUpgradeResponse`, each exposing `getExtensions()` / `setExtensions(List<ExtensionConfig>)`. [VERIFIED: Jetty 12 javadoc for `ServerUpgradeRequest` and `ServerUpgradeResponse`]

**The `ExtensionConfig` API (verified from Jetty 12 javadoc):**

```java
// Constructor with parameterised name
ExtensionConfig cfg = new ExtensionConfig("permessage-deflate; server_no_context_takeover");

// Or explicit: name + params
ExtensionConfig cfg = new ExtensionConfig("permessage-deflate");
cfg.setParameter("server_no_context_takeover"); // value-less flag parameter
```

**Spring Boot integration path.** In the project's current `WebSocketConfig.registerWebSocketHandlers`, the handler is registered via Spring's `WebSocketHandlerRegistry`. To hook into Jetty's extension negotiation we need one of:

1. **`JettyRequestUpgradeStrategy` + Spring `HandshakeHandler`** — Spring's `DefaultHandshakeHandler` takes a `RequestUpgradeStrategy`. Jetty's `JettyRequestUpgradeStrategy` (from `spring-websocket`) has hooks onto Jetty's creator path. However, this API was reorganised in Spring 6; check post-swap whether `JettyRequestUpgradeStrategy` can still accept an extension-config customizer. [ASSUMED — Spring Framework 6.2 API needs verification at implementation time]

2. **`WebServerFactoryCustomizer<JettyServletWebServerFactory>`** — register a customizer bean that adds a `JettyWebSocketServletContainerInitializer` configurator. This is the path that grants direct access to Jetty's `JettyWebSocketServerContainer` (or, in Jetty 12.1+, `ServerWebSocketContainer`) at `ServletContextHandler` start time. [CITED: Jetty 12 programming guide WebSocket server; CITED: Spring Boot reference chapter on customizing embedded servers]

**Recommended approach** (option 2) — the planner should set up a `WebSocketUpgradeFilter` + `ServerWebSocketContainer` pair via a `WebServerFactoryCustomizer<JettyServletWebServerFactory>`. Inside the customizer, the `WebSocketCreator` lambda enforces `permessage-deflate`:

```java
// INSIDE JettyServerExtensionCustomizer (sketch — planner verifies exact APIs)
WebSocketCreator creator = (req, resp, callback) -> {
    List<ExtensionConfig> requested = req.getExtensions();
    boolean hasDeflate = requested.stream()
            .anyMatch(e -> "permessage-deflate".equals(e.getName()));
    if (!hasDeflate) {
        // D-33 fail-fast: refuse the upgrade
        callback.failed(new UpgradeException(400, "permessage-deflate required"));
        return null;
    }
    // D-31: force server_no_context_takeover on negotiation
    ExtensionConfig negotiated = new ExtensionConfig("permessage-deflate");
    negotiated.setParameter("server_no_context_takeover");
    resp.setExtensions(List.of(negotiated));
    return /* your handler adapter */;
};
```

The exact API to attach this creator to `/ws/world` under Jetty 12 is `ServerWebSocketContainer.addMapping("/ws/world", creator)` once the container is fetched inside a `LifeCycle.Listener` on the `ServletContextHandler`. Planner verifies by consulting `jetty-examples` repo under `embedded/ee10-websocket-jetty-server` branch. [CITED: github.com/jetty/jetty-examples]

### Client-side permessage-deflate (D-31 + D-33)

**Spring's `StandardWebSocketClient` has NO public extension API.** [VERIFIED: Spring Framework 6.2 javadoc] The only `executeInternal(..., extensions, ...)` hook is `protected`.

**Recommended path:** switch `BotClient` to Jetty's native `WebSocketClient`:

```java
// Jetty 12 API — verified from javadoc
WebSocketClient client = new WebSocketClient();
client.start();

ClientUpgradeRequest req = new ClientUpgradeRequest();
req.addExtensions("permessage-deflate; server_no_context_takeover");

Session session = client.connect(myEndpoint, URI.create("ws://host/ws/world"), req).get();
```

**D-33 enforcement on client side.** After `connect()` resolves, inspect the negotiated extensions via the session's upgrade response headers. If the response doesn't contain `permessage-deflate` in `Sec-WebSocket-Extensions`, close the session immediately. The session's `UpgradeResponse` is accessible via `Session.getUpgradeResponse().getHeaders().get("Sec-WebSocket-Extensions")`.

**Alternative (subclassing approach):** If the planner wants to keep Spring's `StandardWebSocketClient` for consistency with the test suite's existing WS client usage, subclass it and override `executeInternal` to append a `permessage-deflate` `WebSocketExtension` before delegating to super. This works but depends on a `protected` API. Verified-fragile: Spring 7 could repackage. Prefer Jetty native unless compatibility constrains.

### Handshake Inspection Test (D-32)

To assert `Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover` on the upgrade response, use a raw HTTP client to perform the upgrade and read the response headers before handing off to a WebSocket layer. Standard pattern with `java.net.http.HttpClient`:

```java
// Sketch — planner refines
HttpClient http = HttpClient.newHttpClient();
HttpRequest upgrade = HttpRequest.newBuilder(URI.create("http://localhost:PORT/ws/world"))
        .header("Connection", "Upgrade")
        .header("Upgrade", "websocket")
        .header("Sec-WebSocket-Version", "13")
        .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
        .header("Sec-WebSocket-Extensions", "permessage-deflate; server_no_context_takeover")
        .GET().build();
HttpResponse<Void> resp = http.send(upgrade, BodyHandlers.discarding());

assertEquals(101, resp.statusCode());
String extHeader = resp.headers().firstValue("Sec-WebSocket-Extensions").orElseThrow();
assertTrue(extHeader.contains("permessage-deflate"));
assertTrue(extHeader.contains("server_no_context_takeover"));
```

This test must run under `@SpringBootTest(webEnvironment = RANDOM_PORT)` and use the `LocalServerPort`. It's the one point of truth for whether negotiation actually happened — bot client assertions are downstream of the upgrade. Name the test `WebSocketDeflateHandshakeIntegrationTest`.

### Fail-fast Enforcement (D-33) Tests

Two tests needed (both in `src/test/java/com/paralife/websocket/`):

1. **`ServerRefusesUpgradeWithoutDeflateTest`** — send an upgrade request WITHOUT `Sec-WebSocket-Extensions`; assert server responds 400 (or equivalent rejection code) rather than 101. Uses raw `HttpClient` as above.
2. **`BotClientClosesOnMissingServerDeflateTest`** — stand up a stub WS server that completes the upgrade but does NOT echo back the deflate extension. Start a `BotClient` pointed at it. Assert the client closes the session within a short timeout, and the `WebSocketSession.isOpen()` returns `false`.

### Compatibility caveats

- Spring Boot 3.2+ ships Jetty 12. Spring Boot 3.4.4 pins Jetty 12.0.x (specific patch verified via `./gradlew dependencyInsight` at implementation). [CITED: github.com/spring-projects/spring-boot/wiki Spring Boot 3.4 Release Notes]
- Jetty 12 repackaged the WebSocket API: `PerMessageDeflateExtension` moved from `org.eclipse.jetty.websocket.common.extensions.compress` (Jetty 9) to `org.eclipse.jetty.websocket.core.internal` (Jetty 12). You don't usually reference the class directly — `ExtensionConfig("permessage-deflate")` resolves the factory internally. But if a low-level integration test needs to mock an extension, the package path matters. [VERIFIED: Jetty 12 source repository path]
- `JettyWebSocketServerContainer` (Jetty 10/11 name) is gone in Jetty 12; replacement is `ServerWebSocketContainer` in `org.eclipse.jetty.websocket.server`. Any stale tutorial referencing the old class won't compile. [VERIFIED: Jetty 12 javadoc]
- Known issue: large permessage-deflate payloads had a hang bug in Jetty 10.0.7 (resolved); current 12.0.x is clean but monitor under load. [CITED: github.com/jetty/jetty.project issue #7351]

## Codec Architecture

### Design shape

Package `com.paralife.codec`. **No Spring annotations. No bean scanning. Pure static methods or utility instances. No hidden state (D-41 — `server_no_context_takeover` disables stateful deflate, so the codec cannot rely on cross-frame context either).**

Sealed interface for decoded frames, mirroring the existing `Messages` pattern:

```java
package com.paralife.codec;

public sealed interface Frame {
    record RegisterFrame(char entityType) implements Frame {} // 'C' / 'M' / 'S'
    record SyncFrame(String entityId, List<ActiveEffect> effects) implements Frame {}
    record TickFrame(
            long tickId,
            int curX, int curY,
            int energy, int maxEnergy,
            int sensorRadius,
            List<CellEntry> cells,          // may be empty; missing `s` block
            Optional<StateChange> change,   // `c` block
            List<ActiveEffect> effects,     // `f` block
            List<Event> events,             // `v` block
            Optional<PoolSnapshot> pool,    // `p` block
            List<RosterMember> roster       // `g` block
    ) implements Frame {}
    record ActionFrame(char verb, Optional<String> arg) implements Frame {} // arg is numpad digit or 3-char rank string
    record ErrorFrame(int code, Optional<String> message) implements Frame {}
}

public record CellEntry(
        Coord coord, int presence, Optional<KindData> kind,
        OptionalInt entityState, OptionalInt envState) {}
public sealed interface Coord {
    record Numpad(char digit) implements Coord {}   // '1'-'9'
    record Relative(int dx, int dy) implements Coord {} // ±63 each
    record Absolute(int x, int y) implements Coord {}   // 0..4095 each
}
public sealed interface KindData {
    record Simple(char code) implements KindData {}          // C/M/S/D/N/T/0-5/F
    record RockSolo() implements KindData {}                  // R alone
    record RockRun(char direction, int additionalCount) implements KindData {} // R<dir><count>
}
// Event, ActiveEffect, StateChange, PoolSnapshot, RosterMember — similarly shaped
```

This mirrors the existing `sealed interface Messages` pattern in `com.paralife.websocket.Messages`. Record-heavy, no setters, pattern-matching in consumers.

### API surface

```java
public final class PerceptionCodec {
    public static String encode(Frame f) { ... }
    public static Frame decode(String s) throws CodecException { ... }
    // Called at known frame boundary — handler strips the leading 'T|' etc.
    // No streaming interface needed — WebSocket messages are already frame-sized.
}
```

Error-handling contract: decode throws `CodecException` on malformed input; server maps to `E|400` response, client logs + disconnects.

### Parser style recommendation

**Use a single-pass index-based parser over the input `String`, not `split("|")` or `String.split(",")`.** Rationale:

1. `split` allocates a `String[]` per call + one `String` per token — at ~100 bots × ~5 blocks × ~20 tokens × 2 ticks/sec = ~20K allocations/sec just for tokenisation. A single `int idx` cursor is zero-allocation.
2. LL(1) + known context slot → no lookahead buffer needed; the next byte unambiguously determines the next production (per §2, §6.3, §8.1.4).
3. `StringBuilder` for encoding is standard, with pre-sized initial capacity. For `encode`, a heuristic `initialCapacity = 64 + cellCount * 6` covers typical frames in one allocation.

Skeleton:

```java
// Decode — single index cursor
static Frame decode(String s) {
    int i = 0;
    char type = s.charAt(i++);
    // dispatch on type...
    i++; // consume '|'
    // etc.
}

// Encode — pre-sized StringBuilder
static String encode(TickFrame f) {
    StringBuilder sb = new StringBuilder(128);
    sb.append('T').append('|');
    writeBase64Padded(sb, f.tickId(), 4);
    sb.append('|');
    writeBase64Padded(sb, f.curX(), 2);
    writeBase64Padded(sb, f.curY(), 2);
    // etc.
    return sb.toString();
}
```

**Base64 lookup tables** (single shared, per §1 alphabet):

```java
public final class Base64Codec {
    public static final char[] INT_TO_CHAR = new char[64];
    public static final int[] CHAR_TO_INT = new int[128];
    static {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-";
        Arrays.fill(CHAR_TO_INT, -1);
        for (int i = 0; i < 64; i++) {
            char c = alphabet.charAt(i);
            INT_TO_CHAR[i] = c;
            CHAR_TO_INT[c] = i;
        }
    }
    public static int decodeDigit(char c) {
        int v = c < 128 ? CHAR_TO_INT[c] : -1;
        if (v < 0) throw new CodecException("Invalid base64 char: " + c);
        return v;
    }
}
```

**Existing alphabet helper check.** Searched the codebase (`Grep` for `base64`, `intToChar`, `alphabet`) — no existing helper found. Phase 14 status-bitmask code uses plain hex/byte ops, not base64 text. Codec adds the alphabet helper fresh. [VERIFIED: grep over src/main/java]

### Presence bitmask parser (§8.1.4)

The lookup table in schema §8.1.4 becomes a switch in `parseCellEntry`. Presence byte is a single char `'0'`-`'9'`, `'A'`-`'F'` etc. (via `decodeDigit`); we only use values 1/2/3 today, bits 2-5 reserved. Switch reads presence, then inspects next char to distinguish solo-R vs run-R vs non-R kind, then consumes 0 / 1 / 2 / 3 state chars per the table. The table-driven approach means each case is 3-5 lines — planner implements as a helper `parseKindBlock(String, int idxRef, int presence)` returning `(CellEntry, newIdx)`.

### Round-trip test (§10, 13 locked vectors)

Parameterised JUnit 5 test is the acceptance oracle:

```java
class PerceptionCodecRoundTripTest {
    @ParameterizedTest(name = "Vector {index}: {0}")
    @MethodSource("vectors")
    void roundTrip(String frame) {
        Frame decoded = PerceptionCodec.decode(frame);
        String reEncoded = PerceptionCodec.encode(decoded);
        assertEquals(frame, reEncoded, "Round-trip byte mismatch");
    }

    static Stream<String> vectors() {
        return Stream.of(
            "T|001|0A1B|15/80|2",                                                  // #1 empty tick
            "T|001|0A1B|15/80|2|s61F",                                             // #2 adjacent nutrient
            "T|001|0A1B|15/80|2|s+4-21R62",                                        // #3 rock RLE
            "T|001|0A1B|15/80|2|s+1+13M32",                                        // #4 mixed-status
            "T|001|0A1B|15/80|2|cC:7A|vS",                                         // #5 bonding + repro
            "T|004|0A1B|15/80|2|s61R,91F,43C1,+3-21R62,+3+33M32|fF:2E:0F03|v6H3,6N,T3|p120/200|g62,93,+0+21", // #6 full LOCO
            "T|004|0C1E|20/60|1|s21F",                                             // #7 authority-lite FEEDER
            "T|004|0D2F|18/50|v6H3",                                               // #8 passive DEFENDER minimal
            "T|001|0A1B|15/80|2|fF:2E:0F03|v+0F-03L5",                             // #9 FLEEING + lightning
            "S|7A|S:1Fg8,I:1Ef0",                                                  // #10 resync
            "T|005|0A1B|30/100|2|g62,93,+0+21|v6N,9N",                             // #11 multi-alarm LOCO
            "T|001|0A1B|15/80|2|s+2+022",                                          // #12 env-only cell
            "T|001|0A1B|15/80|2|s43R824,124,-1-124"                                // #13 RLE + env supplements
        );
    }
}
```

**Critical rule:** every decoder path must be reachable from at least one vector. Coverage ordering matters. Vectors 4, 6, 9, 13 are the stressors — they exercise entityState+envState combo (4), full LOCO frame with roster+pool+events+effects+RLE (6), FLEEING abs-coord trailing context with relative-coord lightning event (9), and RLE-with-per-cell-env-supplements (13).

### Edge-case pitfalls catalogued

- **Vector 13 (RLE-with-env-supplements):** parser must NOT try to attach envState of supplementary cells back into the RLE starter's run. The schema is explicit — RLE is kind-only, starter's envState applies only to starter, supplements are separate presence=2 entries at their own coords. Test must assert this (decoded form: three `CellEntry` records, not one starter entry with enriched run data).
- **Vector 6 `43C1`:** presence=3 entry with kind=C (non-R, non-digit), entityState omitted (=0), envState=1 (OVERCROWDED). Parser must distinguish "presence=3, non-R, 1 char remaining" = envState only vs "presence=3, non-R, 2 chars remaining" = entityState + envState. The count of remaining chars before next `,` or end-of-block decides.
- **Vector 6 fF `0F03`:** FLEEING abs strike coord in trailing context — 4 chars unsigned base64. `0F03` decodes as x=0×64+15=15, y=0×64+3=3. Confirm parser consumes exactly 4 chars, not 5 (colon-delimited).
- **Vector 9 `v+0F-03L5`:** event with relative coord (-F,-3) → but +0F is 2 chars for dx's `+` sign then sign-base64-value? No — re-read: relative coord is `[+-]<base64 digit><[+-]><base64 digit>` = exactly 4 chars. So `+0F-03` is 6 chars, not matching the shape. Re-inspecting: in vector 9 the event is `v+0F-03L5` — relative coord has to be 4 chars. Likely this is parsed as `+0F-` then `03L5`? That doesn't match either. **This vector needs a second look from the planner — either it's exercising a longer coord form not in §2, or the test vector as written carries a typo in the schema.** Flag for planner clarification. [ASSUMED: vector 9 relative coord reads as `+0F-03`, 6 chars; if so, schema §2 needs a note about extended-range relative coords for lightning events or the vector should be `+0F-0L5` (4-char coord `+0F-0` then `L5`). Planner consults 15-DISCUSSION-LOG or re-opens schema editor.]
- **Base64 alphabet ordering:** `_-` are the final two characters. Decoders that attempt to sort or assume purely alphanumeric may silently fail on runs including these. Round-trip covers once per vector; add a dedicated boundary test that round-trips a frame containing `_` and `-` in magnitude positions.

## Stateless Bot Refactor

### Current BotClient state inventory

Fields on the existing `BotClient` (from `src/main/java/com/paralife/bot/BotClient.java` read in session):

| Field | Kept? | Reason |
|---|---|---|
| `serverUri`, `entityType`, `brain`, `objectMapper` | keep | Constructor-time config |
| `session` | keep | Unavoidable transport state |
| `entityId` | **keep — schema §6.2** | Cached from `S` frame |
| `registered` (boolean) | keep | Latch flag |
| `actionCount`, `perceptionCount` | keep | Metrics |
| `connectedLatch`, `registeredLatch` | keep | Startup sync |
| `objectMapper` | **DELETE** | Jackson tree parsing replaced by codec |

**New fields required:**

| Field | Purpose | Set by |
|---|---|---|
| `currentType` (char) | Cached type for local decisions (schema §6.3.1 — type changes come via `c` block) | `S` (on register), `T.change` (on state-change) |
| (optional) `currentRadius` | Cached sensor radius | `T` header every tick (can also just read per-tick) |

Nothing else migrates server-side — what the bot needs survives as per-tick `T` contents under the locked schema.

### HeuristicBrain refactor — pure function shape

Current `HeuristicBrain.decide(Perception)` is mostly pure already (no fields — checked read). Two deviations from "pure function of frame":

1. **`ThreadLocalRandom.current().nextInt(...)` calls** — legitimate for random-walk tiebreaks but making them deterministic-by-seed would help reproducibility tests. Recommendation: accept `Random` as a constructor arg (default `ThreadLocalRandom.current()`); tests inject seeded `Random`.
2. **`REPRODUCE_THRESHOLD` constant (hardcoded 70)** — should become config or remain constant per D-43 (pure-function-with-constants is fine).

**Dead-branch fix (Phase 09 tech debt #3):**

```java
ParticleType predatorType = preyType.predator() == myType ? myType.predator() : predatorOf(myType);
```

Both branches return `myType.predator()`. Simplify to:

```java
ParticleType predatorType = myType.predator();
```

**New signature:**

```java
public Decision decide(TickFrame frame, char currentType) { ... }
```

No more `Perception` dependency — bot operates on the decoded `TickFrame` directly. `currentType` is passed because the brain's prey/predator relationship math needs it.

### Respawn flow (schema §9 new scope addition #4)

Current `BotClient.afterConnectionClosed` = session fully torn down. New flow:

1. Bot receives `T` frame with `v<...>D` (died event).
2. Client does NOT close the session.
3. Client waits a randomised cooldown (planner picks range; `0..respawnJitterMs` from config).
4. Client sends `r|<entityType>` again.
5. Server responds with `S|<newEntityId>` (success) or `E|429` (respawn cap — separate concern, server tracks).
6. Next `T` frame is for the new entity.

**Server-side changes:**

- `WorldWebSocketHandler.handleAction` currently rejects `r` frames outside the initial register state. New FSM: session is in `Alive` (has `entityId`) or `Dead` (no `entityId`, awaiting `r`). Death path in the tick pipeline (`SimulationEngine` + `BotRegistry.unregisterBySession` hook) must clear `entityId` without closing the session. Register handler must accept `r` in the `Dead` state.
- Per-session respawn cap: add field `respawnsThisSession` to session attributes; compare against a config cap (planner picks, e.g. 5); emit `E|429` if exceeded.

### `Messages.java` reshape

Current `Messages` sealed interface carries Jackson annotations and 13 record types spanning both directions. Per D-01 big-bang rollout, this file is substantially reworked:

| Current record | Action under new schema |
|---|---|
| `Welcome` | DELETE — schema §5 drops `W` frame |
| `Tick` (heartbeat) | DELETE — global stats move to M005 |
| `Registered` | DELETE — collapsed into `S` (schema §5, D-45 reversal) |
| `Error` | KEEP — maps to `E\|<code>[\|<msg>]` wire frame |
| `Perception` | DELETE — replaced by `Frame.TickFrame` in `com.paralife.codec` |
| `ActionResult` | DELETE — not in schema inventory §5 (no `a_result` frame); actions are fire-and-forget |
| `Register` | DELETE — replaced by `Frame.RegisterFrame` |
| `Heartbeat` | DELETE — not in schema §5 |
| `Action` | DELETE — replaced by `Frame.ActionFrame` |
| `CompositePerception` / `CompositeAction` / `CompositeJoined` | DELETE — schema §5 merges composite surface into the single `T` frame + authority tier logic |
| `EntityState` / `CellView` | DELETE — replaced by record shapes in `com.paralife.codec` |

End state: `Messages.java` either (a) deleted entirely (codec package carries all frame types), or (b) retained as a thin compatibility layer mapping codec `Frame` subtypes to a migration-period record set. Recommendation: (a), big-bang per D-01. Existing tests referencing `Messages.CellView` etc. migrate to codec types in the test-migration task.

### ActionResolver wire impact

`ActionResolver.queueAction(sessionId, Messages.Action)` → `queueAction(sessionId, Frame.ActionFrame)`. Body of method touches the input only via `.actionType()` / `.direction()` getters, now replaced by `.verb()` / `.arg()`. Planner must update:

- Move direction/verb codes: existing code uses `"move"`/`"consume"`/`"reproduce"`/`"rest"` + compass names; new verbs are single chars `M`/`E`/`A`/`R`/`V`/`L` (schema §8.6). Mapping table:
    - `move` (compass `N`) → `M` + numpad `8`
    - `consume` → `E` (eat; ambiguous with the existing JSON `eat`-free vocabulary, but schema is authoritative)
    - `reproduce` (compass `SE`) → `R` + numpad `3`
    - `rest` → **not in schema §8.6** — no wire representation. Server default = no action this tick. Client sends nothing; server auto-fallbacks per §7 "server auto-picks a fallback if nothing arrives."

## PNG-based Rock Generation

### Library / algorithm

Pure JDK: `javax.imageio.ImageIO.read(InputStream)` returns `BufferedImage`; `BufferedImage.getRGB(x, y)` returns ARGB int; `Raster.getSample(x, y, band)` reads a specific band directly. Luminance from RGB:

```java
int argb = image.getRGB(x, y);
int r = (argb >> 16) & 0xff;
int g = (argb >> 8)  & 0xff;
int b =  argb        & 0xff;
int luminance = (int) (0.299 * r + 0.587 * g + 0.114 * b); // perceptual weighting
```

### PNG format handling

`ImageIO.read` returns `BufferedImage` uniformly regardless of underlying format (indexed palette / RGB / grayscale / alpha). The `getRGB` method converts all to ARGB 32-bit on read — zero special-casing needed. [VERIFIED: javadoc `BufferedImage.getRGB` "returns an integer pixel in the default RGB color model (TYPE_INT_ARGB)"]

**Gotcha to avoid:** calling `getRaster().getSample(x, y, 0)` on an indexed-palette image returns the palette index, not luminance. Use `getRGB()` throughout.

### Pipeline per D-34

```java
public final class RockInitializer {
    private final WorldGrid grid;
    private final RockConfig config;
    private final Random rng;

    void apply() {
        // 1. random file
        String resource = config.textures().get(rng.nextInt(config.textures().size()));
        BufferedImage img = ImageIO.read(getClass().getResourceAsStream(resource));
        // 2. random rotation 0/90/180/270
        img = rotate(img, rng.nextInt(4) * 90);
        // 3. random flip
        int flip = rng.nextInt(3); // 0=none, 1=H, 2=V
        if (flip != 0) img = flip(img, flip);
        // 4. sample + place
        int tileW = img.getWidth(), tileH = img.getHeight();
        for (int y = 0; y < grid.getHeight(); y++) {
            for (int x = 0; x < grid.getWidth(); x++) {
                int lum = luminance(img.getRGB(x % tileW, y % tileH));
                if (lum >= config.rockThreshold()) {
                    grid.setEntity(x, y, new Entity.Rock("rock-" + x + "-" + y));
                }
            }
        }
    }
}
```

### Config binding

```yaml
paralife:
  world:
    rock-seed: 0                    # 0 = Random() unseeded; any non-zero = seeded (D-35)
    rock-density-threshold: 128     # 0..255; default 128 = half brightness
    rock-textures:                  # classpath resources
      - /rocks/perlin-01.png
      - /rocks/perlin-02.png
      - /rocks/perlin-03.png
      - /rocks/perlin-04.png
      - /rocks/perlin-05.png
```

Bound via `@ConfigurationProperties(prefix = "paralife.world")` record. Matches existing `@ConfigurationProperties` record pattern (`GridConfig`, `TickConfig`, etc. per CLAUDE.md).

**Seeded determinism (D-35):** if `rock-seed` ≠ 0, construct `new Random(rockSeed)`; otherwise `new Random()`. Same seed → same file choice → same rotation/flip → same boolean rock grid. Reproducibility test asserts two runs produce the same rock placement set.

### Init pipeline integration

`RockInitializer` runs once after `WorldGrid` is constructed. Options:

1. `@PostConstruct` on the initializer bean with `@DependsOn("worldGrid")` — simplest.
2. Listen for `ApplicationReadyEvent` and apply there — decouples from construction order.

Recommendation: option 1. Existing `FertilityInitializer` (from Phase 13 Plan 03) already uses `@PostConstruct` on a `@Component`; `RockInitializer` follows the same pattern for consistency.

**Ordering constraint:** rock placement must happen BEFORE any bot registration, otherwise `trySetEntity` could lose a race with rocks. Spring default `@PostConstruct` fires during bean initialization, before any WebSocket connections accepted, so this is safe.

### Minimum viable PNG set

Recommend shipping 5 variants, each 64×64 grayscale perlin-threshold PNGs, in `src/main/resources/rocks/`. Five gives enough randomness to avoid obvious repeats across runs at the world-init moment (N=5 files × 4 rotations × 3 flips = 60 unique configurations). Smaller tiles tile naturally on 256×256; larger tiles (128×128) are fine too but increase classpath footprint. Planner picks exact tile dimensions; 64×64 is a solid default.

**Asset sourcing:** planner + user can generate these offline with any perlin tool (e.g. `gimp`, `pillow`, a small one-off script). They're not source-controlled code — ship as binary resources.

## Micrometer Metrics (D-38)

### Import path and bean

`MeterRegistry` is auto-configured by Spring Boot Actuator (already on classpath). Inject into any `@Component`:

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.DistributionSummary;
```

### Counter: `paralife.ws.bytes-saved`

```java
private final Counter bytesSaved;

public TickBroadcaster(MeterRegistry registry, /* ... */) {
    this.bytesSaved = Counter.builder("paralife.ws.bytes-saved")
            .description("Raw - deflated bytes sent per frame")
            .baseUnit("bytes")
            .register(registry);
}

// per-frame send:
int raw = encoded.getBytes(StandardCharsets.UTF_8).length;
int sent = getActualSentBytesFromJettyStats(session); // see caveat below
bytesSaved.increment(raw - sent);
```

**Caveat on sent-byte observation.** The tricky piece: Jetty's `permessage-deflate` extension compresses at frame-write time, so the raw-vs-deflated comparison requires asking Jetty for the post-deflate frame size. Options:

1. **Session statistics** — Jetty 12's `Session` exposes `getMessagesOut()` and (in some configurations) a frame-size accumulator, but deflated byte count isn't always available per-message. Check `org.eclipse.jetty.websocket.core.Session` API at impl time.
2. **Approximation** — record raw bytes sent (known: `encoded.length()`) and use a `DistributionSummary` for raw size; compute bytes-saved on the pipeline from a separate `OutgoingFrames` decorator that wraps the deflated output and counts pre-deflate vs post-deflate. This is a Jetty extension wrapper pattern.
3. **Jetty 12 `PerMessageDeflateExtension` hook** — this extension exposes counters internally; in Jetty 12.0.x it's not publicly queryable per-frame. [ASSUMED — planner verifies at impl time]

**Recommended approach:** accept the approximation path. Instrument at two points — raw codec output bytes (definite) and a Jetty `Session.getOutputStatistics()` rollup (best-effort). If the exact per-frame ratio is opaque, document the limitation and publish `paralife.ws.bytes-saved` as "bytes-saved estimate". Alternative: reserve this metric for a future phase once Jetty 12 exposes a cleaner hook. Planner decides scope trade-off.

### Gauge: `paralife.ws.active-sessions`

Straightforward — wraps `SessionRegistry.size()`:

```java
public SessionRegistry(MeterRegistry registry) {
    // ...
    Gauge.builder("paralife.ws.active-sessions", this, SessionRegistry::getSessionCount)
            .description("Current active WebSocket sessions")
            .register(registry);
}
```

Note: `SessionRegistry` currently has no `MeterRegistry` dep — add it via constructor in this phase.

### DistributionSummary: `paralife.ws.tick-frame-bytes`

```java
private final DistributionSummary frameSize;

public TickBroadcaster(MeterRegistry registry, /* ... */) {
    this.frameSize = DistributionSummary.builder("paralife.ws.tick-frame-bytes")
            .description("Per-tick frame payload size in bytes (raw, pre-deflate)")
            .baseUnit("bytes")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
}

// per-frame send:
frameSize.record(encoded.getBytes(StandardCharsets.UTF_8).length);
```

Publish percentiles 50/95/99 — standard for catching codec regressions.

### Verification approach

`MetricsEndpointIntegrationTest` — `@SpringBootTest` with `TestRestTemplate` hitting `http://localhost:PORT/actuator/metrics/paralife.ws.active-sessions`. Assert:

- Metric exists (not 404).
- `measurements` JSON array present.
- After N ticks with M connected bots, value is M.
- `paralife.ws.tick-frame-bytes` count > 0 after a few ticks.

## Test Migration Impact

The 166-test suite must pass after Phase 15 (ROADMAP line 139). Migration impact by test class:

| Test | Impact | Action |
|---|---|---|
| `WebSocketIntegrationTest` | `Welcome`/`Registered` JSON assertions break | Rewrite in codec terms; assert `S\|<entityId>` etc. |
| `TickBroadcasterTest` | Tick heartbeat is deleted (D-02) | Delete this test; its scope moves to M005 |
| `HundredBotIntegrationTest` | JSON parse paths in bot reflect protocol | Test-internal `BotClient` refactor covers this |
| `PerceptionBroadcasterTest` | Class is renamed to `TickBroadcasterTest` (different meaning); `Perception` record gone | Rewrite against codec frames. Keep semantics — it's testing the projection. |
| `BotClientIntegrationTest` | Jackson paths replaced with codec | Refactor under same class |
| `PerceptionActionIntegrationTest` | Direct schema coupling via `Perception`/`Action` | Refactor with codec types |
| `CompositeActionTest`, `CompositePerceptionTest`, `CompositeMovementTest` | Composite message types deleted | Move assertions onto `T` frame + authority tier |
| `LoadTest`, `PopulationDynamicsTest` | Deep — exercise the whole stack | Should pass with zero test-code changes if the codec is correct (integration-level tests shouldn't care about wire format) — but the bots inside them depend on codec-aware `BotClient` |
| All `Environment*Test`, `SimulationEngineTest`, etc. | No WS interaction | No change |

**Estimated breakage:** ~15 tests need direct edits; ~50+ tests go through `BotClient` / `BotLauncher` and benefit transparently from the bot refactor. Remaining ~100 are simulation-internal and unaffected.

**Stabilization tactic:** land the codec + round-trip test FIRST (in isolation — it's a pure-Java package with no Spring deps). Once round-trip green, start the `Messages.java` reshape, which will cascade breakage into the test surface. Fix tests class-by-class rather than big-bang.

## Zero-Trust Filtering — Verification

Schema §8.1 drops neighbour IDs and hides bonded-secondary type (§9 D-28 reversal). Planner must verify `TickBroadcaster.cellToView` (the renamed `PerceptionBroadcaster.cellToView`) emits the locked alphabet:

- Kind codes: `C`, `M`, `S` for solo, `D`/`N`/`T` for bonded-primary (secondary type NOT revealed — this is stricter than current `BONDED_<P>_<S>` string), `0`-`5` for composite members, `R` for rock, `F` for nutrient.
- No occupant id on wire (Phase 14 already passed `displayId`; codec drops it).
- `entityState` bits from the env cache keyed by occupant id (server-side only; id never crosses the wire).

**Test:** `ZeroTrustFilteringTest` parses an outbound `T` frame for a given world state and asserts:
- No entity id strings appear anywhere in the encoded frame bytes.
- For a cell occupied by `BondedPair(primary=CATALYST, secondary=SPORE)`, the encoded kind byte is `D` (not something that would reveal SPORE).
- Self cell at bot's position is never emitted in the `s` block (D-08 still holds).

## Current Code Assessment

Pre-existing code surfaces that affect the refactor:

### Already aligned with Phase 15 (no fight)

- `Entity` sealed interface with `Particle`/`Rock`/`Nutrient`/`BondedPair`/`CompositeMember` — codec maps directly.
- `BuffRegistry.ActiveBuff(BuffType, long expiryTick)` — already carries absolute expiry tick (D-06 zero-conversion).
- `EnvironmentEngine.getCellStatus(pos)` + `getEntityStatus(id)` — per-tick status caches (Phase 14 D-41) feed the codec's envState/entityState bytes directly.
- `SessionRegistry` — ready to have the active-sessions gauge wrapped.
- Vision-scoped OVERCROWDED mask-and-OR in `PerceptionBroadcaster.cellToView` (D-40) — identical logic carries into the renamed broadcaster.

### Will need rework

- `WorldWebSocketHandler` — Jackson `ObjectMapper` removed from read + write paths. Handler uses codec directly. Respawn flow adds FSM.
- `Messages.java` — deleted or thinned substantially (see §Stateless Bot Refactor).
- `BotClient` — raw JSON paths (Phase 09 tech debt #4) eliminated. Minimal cached state. Respawn flow.
- `HeuristicBrain` — dead-branch fix (Phase 09 tech debt #3). Signature change from `Perception` to `TickFrame`. Deterministic-seed ctor injection.
- `PerceptionBroadcaster` → rename `TickBroadcaster`. Projection step swaps from Jackson to codec. Metrics hooks added.
- `TickBroadcaster` (old heartbeat) — DELETED per D-02.
- `WebSocketConfig` — adds `WebServerFactoryCustomizer` for Jetty extension negotiation.
- `build.gradle.kts` — starter-tomcat exclusion + starter-jetty add.
- `application.yml` — `paralife.world.rock-seed`, `rock-density-threshold`, `rock-textures` keys.

### Tech debt opportunities

- Phase 08 tech debt #2 (UNKNOWN type for dead entities in `PerceptionBroadcaster`): opportunistic cleanup per CONTEXT.md. The new codec doesn't need an UNKNOWN type — dead entity's `T` frame carries `vD` event; the self cell is omitted so there's no kind code to emit for a dead self. Natural fix.
- Phase 09 tech debt #3 (`predatorType` dead branch): fixed during `HeuristicBrain` refactor.
- Phase 09 tech debt #4 (`JsonNode`/`LinkedHashMap` in `BotClient`): fixed by codec adoption (D-42).

## Common Pitfalls

### Pitfall 1: Swap breaks WebSocket auto-config (container swap)

**What goes wrong:** After excluding Tomcat and adding Jetty, WebSocket stops working entirely — connection refused or 404 on `/ws/world`.
**Why it happens:** Spring Boot auto-configures WebSocket per-container; there's a small asymmetry in which autoconfiguration class fires under Jetty vs Tomcat. Usually works fine but a stale dep cache or a missing `spring-boot-starter-websocket` (still needed — `starter-jetty` alone doesn't include Spring's WS abstraction) reveals itself as a silent 404.
**How to avoid:** Keep BOTH starters after the swap — `spring-boot-starter-websocket` (Spring abstraction) + `spring-boot-starter-jetty` (container). Run `./gradlew bootRun` and `curl -v http://localhost:8080/actuator/health` + a WebSocket handshake probe before trusting the swap.
**Warning signs:** `NoSuchBeanDefinitionException` on `WebSocketHandler`-related beans; `/actuator/info` shows server as "unknown"; `netstat` shows Jetty listening but `/ws/world` returns 404.

### Pitfall 2: Client's `StandardWebSocketClient` silently skips deflate

**What goes wrong:** Client connects successfully. Server thinks deflate is negotiated. Client thinks deflate is negotiated. Neither actually is — client sent no `Sec-WebSocket-Extensions` header, server didn't enforce, traffic flows uncompressed. Compression metric shows 0 bytes saved.
**Why it happens:** Spring's `StandardWebSocketClient` has no public extension API; a naive "it works" test passes because Spring + Jakarta WebSocket default to no extensions.
**How to avoid:** Use Jetty's native `WebSocketClient` on the client side. Enforce D-33 with a handshake inspection test that fails the build when the negotiated header is missing. Integration test MUST assert presence of `permessage-deflate; server_no_context_takeover` in the response, not just that the session is open.
**Warning signs:** `paralife.ws.bytes-saved` counter reads ~0 under normal load; `Sec-WebSocket-Extensions` response header is absent or empty; wireshark capture shows uncompressed text frames.

### Pitfall 3: Codec mutation across frames (stateful cache)

**What goes wrong:** `permessage-deflate` with `server_no_context_takeover=true` is configured correctly on the wire, but the codec itself caches decoded tokens / frames between calls, creating phantom correlation when client frames arrive out of order.
**Why it happens:** Tempting to optimize the codec with a "last parsed entry id" cache or a symbol table. D-41 explicitly forbids this.
**How to avoid:** Keep the codec pure-static + pure-functional. No fields on codec classes. Review PR for any `private` fields in `com.paralife.codec.*`.
**Warning signs:** Tests pass individually but fail in sequences; race conditions in parallel bot scenarios; bytes-saved metric swings wildly with concurrency.

### Pitfall 4: RLE starter attempts to attach env to whole run

**What goes wrong:** Schema §8.1 specifies RLE is kind-only; the starter's envState applies to starter cell only; other cells in a run need separate `presence=2` entries. A codec that attaches the starter's envState to all run cells produces different decoded output from the wire representation.
**Why it happens:** Easy to reason "3 rocks in a run, one is toxic" and conclude the toxic bit covers the run. It doesn't — each rock needs its own env entry.
**How to avoid:** Round-trip vector 13 (`s43R824,124,-1-124`) asserts exactly this. Run the test.
**Warning signs:** Vector 13 decode produces one `CellEntry` with three `envState` bytes instead of three `CellEntry` records. Encoder emits incorrectly when re-encoding.

### Pitfall 5: FLEEING abs-coord parser consumes next token's leading digit

**What goes wrong:** FLEEING effect stores abs strike coord in trailing `<ctx>` slot: `F:<expiryTick>:<XXYY>`. Abs coord is 4 chars unsigned base64, some of which start with digits. If parser isn't careful about slot width, it can eat into the next event/effect.
**Why it happens:** Relative coords start with `+`/`-` so they self-delimit. Absolute coords in context slots rely on fixed-width; a buggy parser may misparse.
**How to avoid:** §2 parser disambiguation rule: absolute coords only appear in fixed positional slots. Treat the `:<ctx>` slot of an `F:` effect as "consume exactly 4 chars, then expect `,` or end-of-block." No scanning.
**Warning signs:** Vector 9 decode produces wrong event list; vector 6's `fF:2E:0F03` becomes misaligned with following `v` block.

### Pitfall 6: Rock init race with bot registration

**What goes wrong:** First bot to connect races with `RockInitializer` — ends up spawning on top of a rock, or rocks end up overwriting a Particle.
**Why it happens:** `@PostConstruct` vs WebSocket accept ordering is usually correct (Spring finishes bean init before binding the server port), but `@ConditionalOnProperty` or lazy initialization can invert ordering.
**How to avoid:** Add a readiness barrier — `TickEngine.auto-start` is already `true`; gate `@PostConstruct` so `RockInitializer` runs before `TickEngine.@PostConstruct` fires. Concrete: set `@DependsOn("rockInitializer")` on `TickEngine`, or use a `SmartInitializingSingleton` with Phase 14's FertilityInitializer pattern.
**Warning signs:** Rock test run shows nutrient/particle entities on cells that should be rocks; `worldGrid.trySetEntity` returns false where it should return true.

### Pitfall 7: Micrometer meter names with hyphens

**What goes wrong:** `paralife.ws.bytes-saved` — Prometheus converts `-` to `_`; some backends don't. Registered meter name and queried name drift.
**Why it happens:** Micrometer naming conventions differ per backend.
**How to avoid:** Stick to Spring Boot / Micrometer canonical convention (dots as separators, all-lowercase, no hyphens). Rename to `paralife.ws.bytes.saved`, `paralife.ws.tick.frame.bytes` etc. — still readable, no backend-specific coercion surprises.
**Warning signs:** Metrics appear in `/actuator/metrics` with one name and in Prometheus scrape with another.

## Code Examples

### Codec skeleton

```java
// Source: schema §8.1, §10 vectors
package com.paralife.codec;

public final class PerceptionCodec {
    public static String encode(Frame f) {
        StringBuilder sb = new StringBuilder(128);
        switch (f) {
            case Frame.TickFrame t -> encodeTickFrame(sb, t);
            case Frame.SyncFrame s -> encodeSyncFrame(sb, s);
            case Frame.RegisterFrame r -> encodeRegister(sb, r);
            case Frame.ActionFrame a -> encodeAction(sb, a);
            case Frame.ErrorFrame e -> encodeError(sb, e);
        }
        return sb.toString();
    }

    public static Frame decode(String s) {
        ParseCursor c = new ParseCursor(s);
        char type = c.next();
        return switch (type) {
            case 'T' -> parseTickFrame(c);
            case 'S' -> parseSyncFrame(c);
            case 'r' -> parseRegister(c);
            case 'a' -> parseAction(c);
            case 'E' -> parseError(c);
            default -> throw new CodecException("Unknown frame type: " + type);
        };
    }
}
```

### Jetty 12 server extension enforcement

```java
// Source: Jetty 12 javadoc (ServerUpgradeRequest, ServerUpgradeResponse, ExtensionConfig)
// Combined with Spring Boot WebServerFactoryCustomizer pattern
@Configuration
public class JettyDeflateCustomizer {
    @Bean
    WebServerFactoryCustomizer<JettyServletWebServerFactory> forceDeflate(
            WorldWebSocketHandler handler) {
        return factory -> factory.addServerCustomizers(server -> {
            ServletContextHandler ctx = (ServletContextHandler) server.getHandler();
            JettyWebSocketServletContainerInitializer.configure(ctx, (servletCtx, container) -> {
                container.addMapping("/ws/world", (req, resp, callback) -> {
                    List<ExtensionConfig> requested = req.getExtensions();
                    boolean hasDeflate = requested.stream()
                            .anyMatch(e -> "permessage-deflate".equals(e.getName()));
                    if (!hasDeflate) {
                        callback.failed(new UpgradeException(400, "permessage-deflate required"));
                        return null;
                    }
                    ExtensionConfig neg = new ExtensionConfig("permessage-deflate");
                    neg.setParameter("server_no_context_takeover");
                    resp.setExtensions(List.of(neg));
                    return new HandlerAdapter(handler);
                });
            });
        });
    }
}
```
Planner verifies exact class names (`JettyWebSocketServletContainerInitializer` vs `WebSocketUpgradeFilter` — Jetty 12 has both paths).

### Jetty 12 client negotiation

```java
// Source: Jetty 12 programming guide — Client WebSocket
WebSocketClient client = new WebSocketClient();
client.start();

ClientUpgradeRequest req = new ClientUpgradeRequest();
req.addExtensions("permessage-deflate; server_no_context_takeover");

Session session = client.connect(myEndpoint, URI.create("ws://localhost:8080/ws/world"), req)
        .get(10, TimeUnit.SECONDS);

// D-33 enforcement
String serverExt = session.getUpgradeResponse().getHeaders().get("Sec-WebSocket-Extensions");
if (serverExt == null || !serverExt.contains("permessage-deflate")) {
    session.close(1002, "Server did not negotiate permessage-deflate");
    throw new IllegalStateException("Compression not negotiated");
}
```

### Parameterised round-trip test

```java
// Source: schema §10
class PerceptionCodecRoundTripTest {
    @ParameterizedTest(name = "Vector {index}")
    @MethodSource("vectors")
    void roundTripsExactly(String wireFrame) {
        Frame decoded = PerceptionCodec.decode(wireFrame);
        String reEncoded = PerceptionCodec.encode(decoded);
        assertEquals(wireFrame, reEncoded);
    }

    static Stream<Arguments> vectors() {
        return Stream.of(
            Arguments.of("T|001|0A1B|15/80|2"),
            // ... all 13 vectors from schema §10
            Arguments.of("T|001|0A1B|15/80|2|s43R824,124,-1-124")
        );
    }
}
```

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|---|---|---|
| A1 | Spring Boot 3.4.4 ships Jetty 12.0.x (specific patch needs verification via `dependencyInsight`) | §Jetty 12 + Spring Boot 3.4.4 Integration | Low — 3.4.x line is documented to ship Jetty 12; patch version unlikely to change API surface |
| A2 | `JettyRequestUpgradeStrategy` under Spring 6 still supports extension customizer hooks | §Server-side permessage-deflate negotiation | Medium — if dropped, planner switches to the `WebServerFactoryCustomizer` path which is already the recommended fallback |
| A3 | Schema vector 9 (`v+0F-03L5`) is syntactically valid as-written | §Edge-case pitfalls | Medium — if the coord is not 4-char relative, schema may need a note or the vector needs an edit. Planner clarifies with user before finalising codec's relative-coord consumer |
| A4 | Jetty 12 does not expose per-frame post-deflate byte count via public `Session` API | §Counter: paralife.ws.bytes-saved | Low — planner can wrap `OutgoingFrames` to count directly if needed; worst case, metric becomes "estimate" rather than exact |
| A5 | `@PostConstruct` on `RockInitializer` runs before WebSocket port binds | §Init pipeline integration | Low — standard Spring lifecycle, `FertilityInitializer` already uses this pattern successfully |
| A6 | 5 PNG rock variants × 4 rotations × 3 flips (60 configurations) provides adequate world variety for MVP | §Minimum viable PNG set | Low — user-adjustable via config; low-cost to add more variants if requested |
| A7 | `ImageIO.read` handles indexed-palette PNGs transparently via `getRGB()` | §PNG format handling | Very low — this is documented JDK behaviour |
| A8 | Respawn per-session cap (e.g. 5) is needed to prevent runaway respawn storms | §Respawn flow | Low — exact cap is a planner/user decision; the schema mentions `E|429` so the mechanism is expected |

## Open Questions (RESOLVED)

1. **Vector 9 coord width:** `v+0F-03L5` — is the relative coord `+0F-0` (4 chars) then event code `3L5`? Or `+0F-03` (6 chars) then `L5`? Schema §2 says relative coords are 4-char, but the vector as written appears 6-char if the event is `L5`. Likely typo/clarification needed.
   - What we know: schema §2 defines relative coords as `[+-]X[+-]Y` where X, Y are single base64 chars → 4 chars total.
   - What's unclear: the parse tree of vector 9's event.
   - Recommendation: planner flags to user during plan-phase review; if typo, correct the vector; if extension, document schema amendment.

   - **RESOLVED:** routed through plan 15-05 Task 1 as a `## CHECKPOINT REACHED` path. Implementer tries literal-string parse first (interpretation: `+0F` and `-03` as 3-char absolute coords in events — new precedent, not 6-char relative). If round-trip fails against the literal string, executor raises the checkpoint to the user. Schema lock is preserved either way; no silent patching.
2. **Jetty 12 per-frame bytes-saved hook:** does `Session.getOutputStatistics()` (or any equivalent) expose post-deflate byte count on Jetty 12.0.x?
   - What we know: the deflate extension internally tracks it; public API exposure is unclear.
   - What's unclear: whether the metric D-38 #1 can be exact or must be estimated.
   - Recommendation: prototype the metric during implementation; if exact not available, ship as estimate with clear naming + documentation.

   - **RESOLVED:** ship `paralife.ws.bytes.saved` as an estimate — record raw encoded payload size as the numerator; the deflated byte count is best-effort via `jetty-websocket-core` internal stats if reachable, otherwise metric carries a comment "best-effort estimate; Jetty 12 does not expose per-frame post-deflate length publicly." Plan 15-10 owns the estimate decision.
3. **Authority-lite FEEDER/ATTACKER/REPRODUCER action path:** schema §7 says "server auto-picks a fallback if nothing arrives." Current `ActionResolver.resolveFeederConsume` already implements auto-consume. Does authority-lite actually change server behaviour (allow bot to override fallback) or is this documentation-only for MVP?
   - What we know: current resolver code is server-autonomous.
   - What's unclear: whether bot-submitted action for FEEDER is wired into resolver in Phase 15, or deferred post-MVP.
   - Recommendation: planner includes one task for wiring `a|E|<numpad>` from an authority-lite FEEDER into `ActionResolver.queueAction` and resolving to a specific target when provided. Leave server-autonomous path as fallback.

   - **RESOLVED:** Phase 15 ships server-autonomous fallback unchanged — authority-lite members receive the full `T` frame at radius 1 (per SCHEMA §7) but the client does NOT submit actions this phase. Action reception wiring is explicitly deferred post-MVP. Plan 15-08 includes the reduced radius and frame shape; plan 15-09 does not add FEEDER/ATTACKER/REPRODUCER action emitters.
4. **Rock PNG source:** shipping binary resources into a Gradle module — do we want to generate them via a one-off script committed to the repo, bundle pre-made PNGs from an artist, or generate procedurally at build time?
   - What we know: no existing rocks assets.
   - What's unclear: project preference for binary resources in src.
   - Recommendation: ship 5 pre-made PNGs in `src/main/resources/rocks/`. Note the generation command in a README alongside the assets. Keeps build fast and deterministic.

   - **RESOLVED:** plan 15-04 commits 5 pre-generated PNGs to `src/main/resources/rocks/` as opaque binaries. Generation script is not part of this phase. File sizes and counts documented in 15-04 Task 1 acceptance criteria.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|---|---|---|---|---|
| Java JDK 21 | Compilation + runtime | ✓ | 21 (toolchain) | — |
| Gradle wrapper | Build | ✓ | project-local `./gradlew` | — |
| `ImageIO` PNG reader | Rock generation | ✓ | JDK built-in | — |
| Jetty 12.0.x | Container after swap | ✓ (via BOM) | managed by SB 3.4.4 | — |
| Micrometer 1.14.x | Metrics | ✓ (via starter-actuator) | managed by SB 3.4.4 | — |
| Spring WebSocket 6.2.x | Handler abstraction | ✓ (via starter-websocket) | managed by SB 3.4.4 | — |

No external services (databases, message brokers, API gateways) required. All dependencies are library-level and transitively managed by Spring Boot BOM.

**Missing dependencies:** None.

## Validation Architecture

### Test Framework

| Property | Value |
|---|---|
| Framework | JUnit 5 (via `spring-boot-starter-test` 3.4.4) |
| Config file | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` |
| Quick run command | `./gradlew test --tests 'com.paralife.codec.*'` (for codec-only quick loop) |
| Full suite command | `./gradlew test` |
| Phase gate | `./gradlew test jacocoTestReport` — full suite green |

### Phase Requirements → Test Map

Since Phase 15 has no R-numbered requirements, map to the ROADMAP success criteria:

| Criterion | Behaviour | Test Type | Automated Command | File Exists? |
|---|---|---|---|---|
| Codec correctness (all 13 vectors) | `encode(decode(vector)) == vector` byte-for-byte | unit (parameterised) | `./gradlew test --tests 'com.paralife.codec.PerceptionCodecRoundTripTest'` | ❌ Wave 0 |
| Codec handles malformed input | Unknown frame type → `CodecException` | unit | `./gradlew test --tests 'com.paralife.codec.PerceptionCodecErrorTest'` | ❌ Wave 0 |
| Handshake negotiates deflate (D-32) | Server response includes `permessage-deflate; server_no_context_takeover` | integration | `./gradlew test --tests 'com.paralife.websocket.WebSocketDeflateHandshakeIntegrationTest'` | ❌ Wave 0 |
| Server refuses without deflate (D-33) | Upgrade without `Sec-WebSocket-Extensions` → 400 | integration | `./gradlew test --tests 'com.paralife.websocket.ServerRefusesUpgradeWithoutDeflateTest'` | ❌ Wave 0 |
| Client closes if server skips deflate (D-33) | Stub server without deflate → `BotClient.isConnected()` goes false | integration | `./gradlew test --tests 'com.paralife.bot.BotClientClosesOnMissingServerDeflateTest'` | ❌ Wave 0 |
| Zero-trust filtering | Outbound `T` frame encodes no entity ids, no bonded-secondary types | unit | `./gradlew test --tests 'com.paralife.engine.ZeroTrustFilteringTest'` | ❌ Wave 0 |
| Rock generation determinism | Same seed → identical rock grid | unit | `./gradlew test --tests 'com.paralife.world.RockInitializerDeterminismTest'` | ❌ Wave 0 |
| Rock PNG format robustness | Indexed-palette / RGB / grayscale / alpha all parse | unit | `./gradlew test --tests 'com.paralife.world.RockInitializerFormatTest'` | ❌ Wave 0 |
| Bot stateless (reachability) | Fixed seed + fixed frame sequence → identical action decisions | unit | `./gradlew test --tests 'com.paralife.bot.HeuristicBrainDeterminismTest'` | ❌ Wave 0 |
| Respawn flow | Post-death session stays open; re-register works within cap | integration | `./gradlew test --tests 'com.paralife.bot.RespawnFlowIntegrationTest'` | ❌ Wave 0 |
| Respawn cap enforcement | Exceeding cap → `E\|429` | integration | (same test class, separate method) | ❌ Wave 0 |
| Metrics exposure | `/actuator/metrics/paralife.ws.active-sessions` returns value; tick-frame-bytes count > 0 after ticks | integration | `./gradlew test --tests 'com.paralife.websocket.MetricsEndpointIntegrationTest'` | ❌ Wave 0 |
| Full test suite | All 166 pre-Phase-15 tests + new tests pass (migrated where needed) | all | `./gradlew test` | migrated — Wave 0 |
| Regression: population dynamics | 300+ tick run with N bots produces stable populations | integration | `./gradlew test --tests 'com.paralife.engine.PopulationDynamicsTest'` | ✅ exists — no change |
| Regression: load test | 100-bot LoadTest still passes (post-Phase-15 bot must be codec-aware) | integration | `./gradlew test --tests 'com.paralife.engine.LoadTest'` | ✅ exists — bot refactor required |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests 'com.paralife.codec.*'` (fast — no Spring context, <2s)
- **Per wave merge:** `./gradlew test` (full 200+ test suite including migrated + new)
- **Phase gate:** `./gradlew test jacocoTestReport` green + `/actuator/metrics/paralife.ws.*` manual smoke via `curl`

### Wave 0 Gaps

- [ ] `src/main/java/com/paralife/codec/PerceptionCodec.java` — codec entry points (stub file + sealed `Frame` hierarchy)
- [ ] `src/main/java/com/paralife/codec/Base64Codec.java` — shared alphabet tables
- [ ] `src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java` — parameterised test with 13 vectors
- [ ] `src/test/java/com/paralife/codec/PerceptionCodecErrorTest.java` — malformed input cases
- [ ] `src/test/java/com/paralife/websocket/WebSocketDeflateHandshakeIntegrationTest.java` — raw `HttpClient` upgrade inspection
- [ ] `src/test/java/com/paralife/websocket/ServerRefusesUpgradeWithoutDeflateTest.java`
- [ ] `src/test/java/com/paralife/bot/BotClientClosesOnMissingServerDeflateTest.java`
- [ ] `src/test/java/com/paralife/engine/ZeroTrustFilteringTest.java`
- [ ] `src/test/java/com/paralife/world/RockInitializerDeterminismTest.java`
- [ ] `src/test/java/com/paralife/world/RockInitializerFormatTest.java`
- [ ] `src/test/java/com/paralife/bot/HeuristicBrainDeterminismTest.java`
- [ ] `src/test/java/com/paralife/bot/RespawnFlowIntegrationTest.java`
- [ ] `src/test/java/com/paralife/websocket/MetricsEndpointIntegrationTest.java`
- [ ] PNG resource files: `src/main/resources/rocks/perlin-0{1,2,3,4,5}.png` (5 variants, 64×64 grayscale)

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---|---|---|
| V2 Authentication | no | Project has no auth — bots connect anonymously by design (toroidal sim, no users) |
| V3 Session Management | partial | WebSocket session id is Spring-assigned UUID; no security-sensitive state carried in it. Respawn cap (§Respawn flow) is a DoS safeguard (V11). |
| V4 Access Control | no | No role-based gating; all connections are equal |
| V5 Input Validation | **yes** | Codec MUST validate wire bytes against §8.1 grammar. Malformed → `E\|400`, not crash / interpretive drift |
| V6 Cryptography | no | No sensitive data; local LAN / trusted network |
| V11 Business Logic | **yes** | Respawn cap prevents a single session spamming `r` frames to drain entity-placement attempts; action-rate limiting is implicit via tick-per-bot |
| V13 API | **yes** | `/actuator/metrics` endpoint exposure — should not surface internal entity ids |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---|---|---|
| Malformed wire bytes crash decoder | Denial of Service | Defensive parsing; throw `CodecException` + `E\|400` response; decoder never mutates server state |
| Buffer overflow via huge `f` block or `g` block | Denial of Service | Jetty `max text message size` limit (existing config); plus codec bounds-check on block entry count |
| Respawn storm from single session | Denial of Service | Per-session respawn cap (§Respawn flow); `E\|429` response |
| Slow-loris WebSocket (open connection, don't send) | Denial of Service | Jetty idle timeout (existing Spring property `server.jetty.max-connections` + idle timeout) |
| Information disclosure via neighbour ids | Information Disclosure | Schema §8.1 drops neighbour ids entirely (D-28 zero-trust preserved) |
| Information disclosure via global world state | Information Disclosure | No global broadcast on bot-facing protocol post-D-02; observer stream deferred to M005 |
| Malicious action injection (wrong verb) | Tampering | Server validates verb ∈ {M, E, A, R, V, L} per schema §8.6; unknown verbs → `E\|400` |
| Actuator exposing internal state | Information Disclosure | `management.endpoints.web.exposure.include` already restricted to `health,info,metrics` — `paralife.ws.*` metrics carry no entity ids. Keep this restriction |

## Sources

### Primary (HIGH confidence)
- Spring Boot 4.x reference (same WebSocket auto-config applies to 3.4.x): https://docs.spring.io/spring-boot/reference/messaging/websockets.html
- Jetty 12 programming guide — WebSocket Server: https://jetty.org/docs/jetty/12/programming-guide/server/websocket.html
- Jetty 12 programming guide — WebSocket Client: https://jetty.org/docs/jetty/12.1/programming-guide/client/websocket.html
- Jetty 12 javadoc — `ServerUpgradeRequest`: https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/websocket/server/ServerUpgradeRequest.html
- Jetty 12 javadoc — `ServerUpgradeResponse`: https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/websocket/server/ServerUpgradeResponse.html
- Jetty 12 javadoc — `ExtensionConfig`: https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/websocket/core/ExtensionConfig.html
- Jetty 12 javadoc — `ServerWebSocketContainer`: https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/websocket/server/ServerWebSocketContainer.html
- Jetty 12 javadoc — `ClientUpgradeRequest`: https://javadoc.jetty.org/jetty-12.1/org/eclipse/jetty/ee9/websocket/client/ClientUpgradeRequest.html
- Spring Boot managed dependency coordinates (Jetty 12.x): https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html
- Spring Framework 6.2 javadoc — `StandardWebSocketClient`: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/socket/client/standard/StandardWebSocketClient.html
- Spring Boot 3.4 Release Notes (Jetty 12 upgrade history): https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.4-Release-Notes
- `15-SCHEMA.md` §1-§13 (the authoritative wire spec)
- `15-CONTEXT.md` carried-forward decisions
- RFC 7692 (permessage-deflate): https://datatracker.ietf.org/doc/html/rfc7692

### Secondary (MEDIUM confidence)
- Jetty WebSocket with Spring Boot tutorial (2022, predates Jetty 12 fully — use as pattern reference, not API reference): https://www.dineshsawant.com/posts/jetty-websocket-with-spring-boot-new/
- Jetty issue #11308 "Disable permessage-deflate in 12.0.5" (confirms extension is in the default factory, must be filtered out explicitly): https://github.com/jetty/jetty.project/issues/11308
- Spring Boot upgrade to Jetty 12 issue #36073: https://github.com/spring-projects/spring-boot/issues/36073

### Tertiary (LOW confidence)
- MDN Sec-WebSocket-Extensions header (informational background only): https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Sec-WebSocket-Extensions

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Spring Boot 3.4.4 BOM determines everything; Jetty 12 confirmed via multiple sources
- Jetty 12 server-side extension API: HIGH — javadoc for `ServerUpgradeRequest` / `ServerUpgradeResponse` / `ExtensionConfig` directly verified
- Jetty 12 client-side extension API: HIGH — `ClientUpgradeRequest.addExtensions` verified
- Spring `StandardWebSocketClient` limitation: HIGH — javadoc directly confirms no public setExtensions method
- Codec architecture: HIGH — schema is LL(1) + locked, parser choice is a standard exercise
- 13 round-trip vectors: HIGH — lifted verbatim from `15-SCHEMA.md` §10
- Rock generation pipeline: HIGH — JDK built-ins only, well-documented
- Metrics wiring: HIGH for Gauge + DistributionSummary, MEDIUM for bytes-saved (Jetty's per-frame post-deflate byte count isn't clearly exposed — may land as an estimate)
- Respawn flow specifics (cap value, cooldown jitter): MEDIUM — planner picks values
- Pitfalls list: HIGH for container swap, codec statelessness, RLE semantics, FLEEING parser; MEDIUM for Micrometer backend naming

**Research date:** 2026-04-20
**Valid until:** 2026-05-20 (30 days — Jetty 12.0.x and Spring Boot 3.4 are stable; Spring Boot 3.5 GA hasn't landed; schema is locked)
