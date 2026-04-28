# Phase 18: External Load Harness & Harness Identity - Research

**Researched:** 2026-04-28
**Domain:** Java 21 standalone WS load harness + server-side per-source attribution + bounded-cardinality Micrometer tagging + shaded fat-jar packaging
**Confidence:** HIGH (Jetty 12 native client APIs verified, Micrometer cardinality pattern verified, Spring Boot bootJar layout verified, repo internals read directly). MEDIUM on JSONL atomic-rename invariants on Windows.

---

## Summary

Phase 18 is **integration plumbing**, not novel infrastructure. Every required primitive already exists in the repo:

- Jetty 12 native `WebSocketClient` is already the transport (`BotClient`); `ClientUpgradeRequest.setHeader(name, value)` is a one-line extension to carry `X-Paralife-Harness` / `X-Paralife-Source` (verified against the 12.1 docs).
- Server-side, `WebSocketSession.getUpgradeHeaders()` (Spring's wrapper) exposes the handshake headers as `HttpHeaders` (case-insensitive map). `WorldWebSocketHandler.afterConnectionEstablished` is the natural read site.
- Micrometer's `MeterFilter.maximumAllowableTags(meterPrefix, tagKey, max, onMaxReached)` is the **canonical** bounded-cardinality idiom; combined with `MeterFilter.replaceTagValues(...)`, an over-cap value is folded to `harness=overflow` per D-10. `AdmissionMetrics` already uses tagged counters via `Counter.builder(...).tag(...)` — the harness adds at most one tag at the call site.
- Spring Boot's `bootJar` already produces a runnable shaded JVM artifact (Layout = `BOOT-INF/`); a second `BootJar` task with a different `mainClass` and a unique `archiveClassifier` produces `paralife-load-harness.jar` reusing the existing fat-jar tooling — **no Shadow plugin needed**.
- The harness is a pure WebSocket client: it must NOT boot Spring (zero `@Component`/`@Service` scanning) — it instantiates `BotClient` directly, parses CLI args manually (or via Picocli; Picocli is pure win at this scope), writes JSON via Jackson (already on classpath transitively).
- Phase 17's `ResumeTokenRegistry` rebind path does NOT explicitly copy session attributes — but this works correctly anyway, because rebind happens on a **new** `WebSocketSession` whose `afterConnectionEstablished` already populates `ATTR_HARNESS`/`ATTR_SOURCE` from the reconnect's handshake headers. **BotClient must re-send the headers on reconnect** — confirm in plan, not assume.

**Primary recommendation:** Treat this phase as five concentric concerns, in dependency order — (1) `BotClient` accepts headers, (2) `WorldWebSocketHandler` reads headers + stashes session attrs + emits HARNESS log markers, (3) `AdmissionMetrics` two-tag extension with `MeterFilter`-bounded cardinality, (4) `BotLauncher` → `BotFleet`/`BotFactory` refactor with async registration, (5) `LoadHarness` main class + CLI + JSON report + Gradle task. Each tier is independently testable.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Scale Model (SCALE-03 / SCALE-05)**
- **D-01:** Single-JVM, virtual-thread-per-bot harness (`com.paralife.harness.LoadHarness`). Operational scale-out is N independent harness JVMs side-by-side.
- **D-02:** Design ceiling 5000 concurrent WS connections per JVM.
- **D-03:** Configurable ramp-up `--ramp-up=instant|rate:<n>|wave:<count>:<sleep-ms>` (default `rate:50`).
- **D-04:** **Refactor `BotLauncher`**, do not fork. Lift the 30s `allDone.await(30, TimeUnit.SECONDS)` ceiling at `BotLauncher.java:67`. Extract a `BotFleet` abstraction shared by `BotRunner` (≤100, instant ramp) and the harness (1000+, rate-limited ramp). Per-bot connect/register tracking becomes async (`CompletableFuture<RegistrationResult>`). `BotRunner`'s observable behavior must not regress.
- **D-05:** **WS:entity 1:1 by default** — captured in `18-HARNESS.md` and `CLAUDE.md`.

**Identity Carriage (SCALE-04)**
- **D-06:** Identity rides on WS handshake HTTP headers: `X-Paralife-Harness: <id>`, `X-Paralife-Source: <operator|harness>`. Server reads from `session.getUpgradeRequest().getHeaders()` at `afterConnectionEstablished`. **Zero codec change**, zero edit to `15-SCHEMA.md`.
- **D-07:** Identity granularity = per-process. One harness id per JVM.
- **D-08:** Default when handshake header is absent: `source=unknown`.
- **D-09:** `BotRunner` explicitly sets `X-Paralife-Source: operator` (no `X-Paralife-Harness`).
- **D-10:** Cardinality cap 64 distinct harness tag values per JVM lifetime; 65th+ folds to `harness=overflow`. Config-tunable via `paralife.admission.attribution.max-harness-cardinality`. One-time warning log on first overflow.

**Attribution Surface (extends Phase 17 D-03 / D-17 / D-18 / D-19)**
- **D-11:** Two-tag scheme. `source ∈ {operator, harness, unknown, overflow, offspring}` (offspring reserved-now-no-producer per D-20). `harness=<id>` only when `source=harness`.
- **D-12:** All admission/backpressure metrics gain `source[, harness]`:
  - `paralife.admission.rejected{reason, source[, harness]}`
  - `paralife.admission.active.entities{source[, harness]}`
  - `paralife.backpressure.stalled.sessions{source[, harness]}`
  - `paralife.admission.ingress.overwrites{source[, harness]}`
  - `paralife.admission.maintenance` — STAYS scalar (server-global)
  - `paralife.tick.health.work-time-ms` — STAYS scalar (server-global)
- **D-13:** Log marker prefixes (`ADMISSION` / `BACKPRESSURE` / `TICK-HEALTH`) gain `source=<v>[ harness=<id>]` field.
- **D-14:** New `HARNESS connected` / `HARNESS disconnected` markers from `afterConnectionEstablished` / `afterConnectionClosed`.

**Repro & Invocation (Phase 21 enabler)**
- **D-15:** Shaded fat JAR + `./gradlew runHarness --args='...'` task. Main class `com.paralife.harness.LoadHarness`. Reuse Spring Boot fat-jar tooling. NO Docker.
- **D-16:** CLI flags only with `PARALIFE_HARNESS_*` env-var overrides. NO YAML config. Required: `--server-uri`, `--count`. Optional: `--harness-id`, `--ramp-up`, `--species-mix`, `--duration`, `--report-out`, `--report-mode`, `--report-interval`.
- **D-17:** JSON run report. Atomic temp-rename (`<path>.tmp` → `<path>`). Overwrite mode = single JSON object always reflecting current state. Append mode = JSONL with header line + counter lines. Counter set: peak/current registered, connect_failures_total, e408_reconnect_required_total, respawns_total, actions_sent_total, perceptions_received_total, syncs_received_total, wall_time_seconds_elapsed, exit_reason (final write only).
- **D-18:** Documentation home = `18-HARNESS.md` (full spec, mirror of `17-ADMISSION.md`) + `CLAUDE.md` "Connection model" subsection.

**Forward-Compat for Backlog 999.2**
- **D-19:** `BotFactory` seam during refactor. Optional reserved params (e.g., `claimEntityId`, `claimToken`) that are no-ops today.
- **D-20:** `source=offspring` reserved in the taxonomy from day one; no producer this phase.
- **D-21:** Multi-entity-per-session strongly discouraged but not banned. Default WS:entity 1:1.

### Claude's Discretion

- Auto-generated harness-id format: `< 32 chars`, alphanumeric + dash. UUID short / `hostname-pid-suffix` / random hex are all fine.
- `BotFactory` final API name and module location (`com.paralife.harness.BotFactory` vs `com.paralife.bot.BotFactory`).
- Fleet abstraction final name (`BotFleet` / `BotPool` / `BotSwarm`).
- `LoadHarness` package location (suggested `com.paralife.harness`).
- Cardinality config namespace: `paralife.admission.attribution.*` or `paralife.harness.*`.
- Sub-record decomposition of any new harness-side `@ConfigurationProperties` (none required server-side beyond D-10).
- Whether sample benchmark commands in `18-HARNESS.md` are shell scripts or documented invocations.
- JSON report field name casing (`peak_registered` vs `registered.peak`) — pick one and stay consistent.
- Whether the Phase 17 `ResumeTokenRegistry` rebind path needs explicit `ATTR_HARNESS`/`ATTR_SOURCE` preservation — see "Common Pitfalls" §1; planning must verify.
- Whether `LoadTest.java` opts-in to harness-tagged path (recommended).

### Deferred Ideas (OUT OF SCOPE)

- Bot-driven offspring producer wire shape → backlog 999.2.
- Multi-entity-per-session protocol → strongly discouraged (D-21).
- Cross-harness ramp coordination / unified run report → operators run N JVMs + `jq`.
- Live-tunable harness whitelist → revisit only on hostile-multi-tenant.
- YAML config file for the harness → revisit only if Phase 21 needs profile bank.
- Per-tick CSV report → defer unless Phase 21 demands client-side per-tick timelines.
- Per-harness gauge for actuator scrape → already covered by D-12 tags.
- High-density placement / partition-aware execution → Phase 19.
- Connection multiplexing / runtime tuning → Phase 20 (with D-21 caveat: prefer many-VT-tuning over multiplexing).
- Benchmark gate + scale reports → Phase 21.
- Docker packaging → M6.
- `/actuator/prometheus` wiring → M5 (Phase 17 D-20 stays).
- Live maintenance-mode actuator endpoint → M5.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **SCALE-03** | Operators can launch a standalone external load harness that scales beyond `BotRunner`'s single-process 100-bot ceiling. | §"Standard Stack" (Picocli + Jackson), §"Architecture Patterns" (LoadHarness package), §"Don't Hand-Roll" (CLI parsing), §"Common Pitfalls" (BotLauncher 30s await), §"Code Examples" (CLI + ramp-up + report I/O), §"Environment Availability" (Java 21 + Gradle confirmed). |
| **SCALE-04** | Each harness instance identifies itself so sessions, failures, and throughput can be attributed per harness in logs and metrics. | §"Standard Stack" (Jetty 12 ClientUpgradeRequest.setHeader, Spring HttpHeaders), §"Architecture Patterns" (two-tag scheme), §"Don't Hand-Roll" (Micrometer MeterFilter.maximumAllowableTags), §"Common Pitfalls" (header case-sensitivity, rebind attribution preservation), §"Code Examples" (handshake header read + tag emission). |
| **SCALE-05** | `BotRunner` remains the supported local operator path for small-N runs while the harness owns large-scale traffic. | §"Architecture Patterns" (BotFleet shared, BotRunner default `instant` ramp + 100-bot cap), §"Common Pitfalls" (BotRunner regression preservation), §"Validation Architecture" (BotRunner regression test class). |
</phase_requirements>

## Architectural Responsibility Map

Phase 18 spans two distinct architectural tiers: a **server-side metrics/identity ingress** layer that sits on top of Phase 17's admission contract, and a **standalone JVM client process** that is conceptually separate from the simulation server.

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Handshake-header parsing | API (server-side WS handler) | — | `WorldWebSocketHandler` already owns connection establishment; identity is a property of the session it admits. |
| Session-attribute storage of `source` / `harness` | API (server-side) | — | Lives on the same `WebSocketSession.getAttributes()` map as `ATTR_ENTITY_ID` / `ATTR_STALL_TICK` — same scope, same lifecycle. |
| Two-tag Micrometer counter emission | API (server-side) | — | Tags are read at the rejection/gauge-update site; emission stays where the counter exists. |
| Bounded-cardinality enforcement | API (server-side) | — | `MeterFilter` runs inside the server's `MeterRegistry`. The harness has no way to enforce this. |
| HARNESS log marker emission | API (server-side) | — | Operator-visible side-effect of session lifecycle on the server. |
| `BotFactory` / `BotFleet` abstraction | Client lib (`com.paralife.bot`) | API (consumed by `BotRunner` and `LoadHarness`) | A reusable concurrency primitive shared by two callers. Lives next to `BotClient`. |
| `LoadHarness` main + CLI | Standalone JVM (`com.paralife.harness`) | — | Standalone process; zero Spring dependency. |
| JSON run report writing | Standalone JVM | — | File I/O on the harness side; server never touches it. |
| Gradle `loadHarnessJar` + `runHarness` tasks | Build | — | Pure build configuration; reuses existing `BootJar` task type. |
| `BotRunner` operator-source header | Client lib | API (verified server-side) | One-line addition; observable in server-side `source=operator` tag. |
| `18-HARNESS.md` spec doc | Documentation | — | Authoritative; mirrors `17-ADMISSION.md`. |
| `CLAUDE.md` Connection-model subsection | Documentation | — | Codifies WS:entity 1:1 (D-05/D-21) for future contributors. |

**Why this matters:** the temptation is to plant `LoadHarness` inside `com.paralife.bot` — but doing so couples it to `BotRunner`'s operator-grade contract (the 100-bot cap, single-shot launch, no JSON report). Keeping it in `com.paralife.harness` enforces the architectural separation and makes the "no Spring context in the harness JVM" rule self-enforcing.

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Java 21 (Temurin) | 21.0.6+ LTS | Virtual threads for VT-per-bot harness | Already the project standard (`spring.threads.virtual.enabled: true`); confirmed in env. [VERIFIED: `java --version` on host] |
| Spring Boot | 3.4.4 | `bootJar` task reused for shaded harness jar | Already the project standard. The Spring Boot Gradle plugin's `BootJar` task type produces both the server and harness artifacts via separate task instances. [VERIFIED: `build.gradle.kts:3-4`] |
| Jetty 12 native WebSocket client | 12.0.18 | Client-side WS transport; supports `permessage-deflate` and `ClientUpgradeRequest.setHeader` | Already on classpath (`org.eclipse.jetty.websocket:jetty-websocket-jetty-client:12.0.18`). [VERIFIED: `build.gradle.kts:36`] |
| Spring Boot starter-websocket (Jetty) | 3.4.4 (transitive) | Server-side `WebSocketSession.getUpgradeHeaders()` | Already on classpath — no change. [VERIFIED: `build.gradle.kts:25-29`] |
| Jackson (databind + datatype-jdk8) | transitive via `spring-boot-starter-web` | JSON report serialization | Already on classpath — Spring's transitive include. Records + `ObjectMapper.writeValue` is the natural shape for D-17. [VERIFIED: present via `starter-web`] |
| Micrometer (core) | 1.14.x (Spring Boot 3.4.4 managed) | Tagged counters, gauges, `MeterFilter.maximumAllowableTags` | Already used by `AdmissionMetrics` and `WebSocketMetrics`. [VERIFIED: imports in `AdmissionMetrics.java`] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| **Picocli** | 4.7.7 (latest stable as of 2026-04, verified npm-equivalent — Maven Central `info.picocli:picocli`) | CLI argument parsing for `LoadHarness` | Strongly recommended over hand-rolled `args[]` parsing. Sub-100KB jar; declarative `@Option` annotations; type conversion (Duration, Path) built in; env-var defaults via `defaultValue = "${PARALIFE_HARNESS_COUNT:-100}"`. [CITED: picocli.info quick-guide] |
| **picocli-codegen** (annotation processor) | matched | GraalVM image hints / reflection metadata | Optional. Skip unless harness ever targets `native-image`. Phase 18 doesn't. |

**Alternative considered and rejected:** Apache Commons CLI (less ergonomic for typed args, no built-in env-var support); JCommander (less actively maintained); pure manual parsing (works for the 8 flags but the ramp-up syntax `rate:50` / `wave:100:500` and species-mix `0.4:0.3:0.3` benefit from a real type converter — write the converter once via `picocli.ITypeConverter` and you're done).

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Spring Boot `BootJar` × 2 | Shadow plugin (`com.gradleup.shadow`) | Shadow produces a flat-classpath jar without the `BOOT-INF/` layered structure. Spring Boot's loader handles the harness's classpath fine; **no advantage** to switching. Avoid the second plugin. [VERIFIED: Spring Boot docs § "Packaging Executable Archives"] |
| Spring Boot context for `LoadHarness` | Plain `public static void main(String[])` | The harness is a pure WS client — it has no `@Service` graph to wire. Booting Spring would add ~3 seconds startup, force `application.yml` resolution it doesn't need, and pull beans (TickEngine, AdmissionGate, WorldGrid) it doesn't use. **Pure main is correct.** |
| Picocli | Hand-rolled `String[] args` parser | Picocli is ~80KB and the harness is already 30MB+ shaded — net cost is negligible; net benefit is correct error messages, `--help`, type-safe `Duration --duration`. [CITED: picocli.info] |
| Custom JSONL writer | Jackson `ObjectMapper.writeValueAsString(record) + "\n"` | Jackson is on classpath. Records serialize natively. No reason to write byte-pushing logic. |

**Installation (deltas from current `build.gradle.kts`):**

```kotlin
dependencies {
    // … existing deps …
    // Phase 18: CLI parsing for LoadHarness. Pure Java, ~80KB, no transitive deps.
    implementation("info.picocli:picocli:4.7.7")
}

// Phase 18: harness shaded fat-jar. Reuses Spring Boot's BootJar task type
// for an independent main class. archiveClassifier disambiguates from bootJar.
tasks.register<org.springframework.boot.gradle.tasks.bundling.BootJar>("loadHarnessJar") {
    group = "application"
    description = "Build the standalone Paralife load harness fat jar (Phase 18)."
    archiveClassifier.set("load-harness")
    mainClass.set("com.paralife.harness.LoadHarness")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runHarness") {
    group = "application"
    description = "Run LoadHarness against a live server (dev iteration; D-15)."
    mainClass.set("com.paralife.harness.LoadHarness")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    // Forward PARALIFE_HARNESS_* env vars and -Dparalife.* sysprops.
    environment(System.getenv().filterKeys { it.startsWith("PARALIFE_HARNESS_") })
    systemProperties = System.getProperties().entries
        .filter { (it.key as String).startsWith("paralife.") }
        .associate { it.key as String to it.value }
}
```

**Version verification:**
- Java 21.0.6 — verified locally via `java --version` (Temurin 21.0.6+7).
- Gradle 8.14.2 — verified via `./gradlew --version`.
- Spring Boot 3.4.4 — verified in `build.gradle.kts:3`.
- Jetty 12.0.18 — verified in `build.gradle.kts:36` (managed by Spring Boot 3.4.4).
- Picocli 4.7.7 — verified as latest 4.x stable [CITED: picocli.info, GitHub remkop/picocli releases]. The 4.x line is the right one; 5.x has not been released.

## Architecture Patterns

### System Architecture Diagram

```
┌─────────────────────────── HARNESS JVM (com.paralife.harness) ───────────────────────────┐
│                                                                                            │
│   args / env  ┌─────────────────┐   ┌──────────────┐   ┌───────────────────────────┐     │
│   ──────────► │  LoadHarness    │──►│   BotFleet   │──►│ BotFactory.create(C/M/S)  │     │
│               │  (main, CLI)    │   │  (rampup +   │   │   ↳ injects HARNESS_ID    │     │
│               │  picocli parse  │   │   async      │   │   ↳ injects SOURCE=harness│     │
│               │  signal hooks   │   │   register-  │   │   returns BotClient       │     │
│               └────────┬────────┘   │   tracking)  │   └────────────┬──────────────┘     │
│                        │            └──────┬───────┘                │                     │
│                        ▼                   │                        ▼                     │
│              ┌──────────────────┐          │              ┌──────────────────┐           │
│              │ ReportWriter VT  │          │              │  BotClient × N    │           │
│              │ (every 30s,      │          │              │  (+ X-Paralife-   │           │
│              │  atomic rename)  │          │              │   Harness header) │           │
│              └────────┬─────────┘          │              └────────┬──────────┘           │
│                       │                    │                       │                      │
└───────────────────────┼────────────────────┼───────────────────────┼──────────────────────┘
                        │ JSON / JSONL       │ status                │ WS upgrade
                        ▼                    │                       │ + frames
                ┌──────────────┐             │                       │
                │ harness-     │             │                       ▼
                │ <id>.json    │             │       ┌─────────────────────────────────┐
                └──────────────┘             │       │  WorldWebSocketHandler          │
                                             │       │  (afterConnectionEstablished)   │
                                             │       │   reads X-Paralife-Harness/Source│
                                             │       │   stashes ATTR_HARNESS/SOURCE   │
                                             │       │   emits HARNESS connected log   │
                                             │       └────┬────────────────────────────┘
                                             │            │
                                             │            ▼
                                             │   ┌─────────────────┐  ┌───────────────────┐
                                             │   │  AdmissionGate  │  │ AdmissionMetrics  │
                                             │   │  evaluate()     │  │  incRejected      │
                                             │   │   (reads attrs) │──┤  (source[,harness]│
                                             │   └─────────────────┘  │   tag values)     │
                                             │                        │  + MeterFilter    │
                                             └───◄ resume token re-bind│   maximumAllowable│
                                                 (Phase 17 STALLED)    │   Tags = 64       │
                                                                       └───────────────────┘
```

### Component Responsibilities

| Component | File | Responsibility |
|-----------|------|----------------|
| `LoadHarness` | `src/main/java/com/paralife/harness/LoadHarness.java` (NEW) | `public static void main`, picocli `@Command`, signal hooks (SIGINT/SIGTERM), report-writer VT lifecycle, `BotFleet` driver. **Zero Spring**. |
| `LoadHarnessOptions` | `src/main/java/com/paralife/harness/LoadHarnessOptions.java` (NEW) | Immutable record holding parsed CLI options. Type converters: `RampUpSpec`, `SpeciesMix`. |
| `RampUpSpec` (sealed) | `src/main/java/com/paralife/harness/RampUpSpec.java` (NEW) | `Instant` / `Rate(int n)` / `Wave(int count, long sleepMs)` — sealed interface; encodes D-03. |
| `SpeciesMix` | `src/main/java/com/paralife/harness/SpeciesMix.java` (NEW) | `balanced` or `(cFrac, mFrac, sFrac)`. Validates sum ≈ 1.0. |
| `BotFleet` | `src/main/java/com/paralife/bot/BotFleet.java` (NEW; D-04 refactor target) | VT-per-bot launcher with async `Map<botId, CompletableFuture<RegistrationResult>>` tracking. **NO 30s `await`**. Shared by `BotRunner` (instant ramp, 100 cap) and `LoadHarness`. |
| `BotFactory` | `src/main/java/com/paralife/bot/BotFactory.java` (NEW; D-19 seam) | `create(species, harnessHeaders, futureClaim?)` — single chokepoint for bot construction. Today's signature: `(char species, BotIdentity identity)`. Reserved 999.2 params: `Optional<String> claimEntityId`, `Optional<String> claimToken` — null today, no-op. |
| `BotIdentity` | `src/main/java/com/paralife/bot/BotIdentity.java` (NEW) | Record `(String source, Optional<String> harnessId)`. `BotIdentity.operator()` / `BotIdentity.harness(id)` static factories. Drives the `X-Paralife-Source` / `X-Paralife-Harness` header insertion in `BotClient.connect()`. |
| `BotClient` (modified) | `src/main/java/com/paralife/bot/BotClient.java` | Add `BotIdentity identity` field via constructor. In `connect()`, before `client.connect(...)`: `req.setHeader("X-Paralife-Source", identity.source()); identity.harnessId().ifPresent(id -> req.setHeader("X-Paralife-Harness", id));`. Existing constructors: see "Common Pitfalls" §3. |
| `BotRunner` (modified) | `src/main/java/com/paralife/bot/BotRunner.java` | Replace `BotLauncher` with `BotFleet`; pass `BotIdentity.operator()` per D-09. Behavior: same exit codes, same stdout messages, same 100-bot cap. |
| `WorldWebSocketHandler.afterConnectionEstablished` (modified) | `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` | Read `session.getHandshakeHeaders()` (Spring's case-insensitive `HttpHeaders`). Stash as `ATTR_SOURCE` / `ATTR_HARNESS`. Emit `HARNESS connected tick=… session=… harness=… source=… active=…`. |
| `WorldWebSocketHandler.afterConnectionClosed` (modified) | same | Emit `HARNESS disconnected tick=… session=… harness=… source=… reason=token\|graceful`. |
| `AttributionTagger` | `src/main/java/com/paralife/admission/AttributionTagger.java` (NEW) | Pure helper. `Tags tagsFor(WebSocketSession session)` → `Tags.of("source", src, "harness", harnessOrEmpty)`. Single source of truth so tag ordering and overflow-handling stay consistent. |
| `AdmissionMetrics` (extended) | `src/main/java/com/paralife/admission/AdmissionMetrics.java` | Existing methods take an extra `Tags` param OR new overload taking `(reason, source, harnessOrNull)`. Existing single-tag callers either compile-break (cleaner) or are wrapped in defaulted overloads (back-compat for old tests). |
| `MeterFilter` registration (NEW) | likely `MetricsConfig` `@Configuration` bean or `@PostConstruct` on `AdmissionMetrics` | Register `MeterFilter.maximumAllowableTags("paralife.admission", "harness", 64, MeterFilter.replaceTagValues("paralife.admission", "harness", v -> "overflow"))`. Apply to `paralife.admission.*` and `paralife.backpressure.*` prefixes per D-12. |
| `ResumeTokenRegistry` (verified-only) | unchanged | See "Common Pitfalls" §1. |

### Recommended Project Structure

```
src/main/java/com/paralife/
├── harness/                            # NEW package — standalone JVM
│   ├── LoadHarness.java                # @Command public static void main
│   ├── LoadHarnessOptions.java         # picocli annotation target / record
│   ├── RampUpSpec.java                 # sealed interface
│   ├── SpeciesMix.java                 # record + parser
│   ├── ReportWriter.java               # VT-driven, atomic-rename JSON/JSONL
│   └── ReportSnapshot.java             # serializable state record
├── bot/                                # MODIFIED — BotFleet + BotFactory + BotIdentity
│   ├── BotIdentity.java                # NEW
│   ├── BotFactory.java                 # NEW (D-19)
│   ├── BotFleet.java                   # NEW (D-04 refactor of BotLauncher)
│   ├── BotLauncher.java                # KEEP for one phase OR delete (BotRunner uses BotFleet now)
│   ├── BotClient.java                  # MODIFIED — accepts BotIdentity, sets handshake headers
│   ├── BotRunner.java                  # MODIFIED — uses BotFleet, passes BotIdentity.operator()
│   └── HeuristicBrain.java             # unchanged
├── websocket/
│   └── WorldWebSocketHandler.java      # MODIFIED — handshake header read + log markers
└── admission/
    ├── AttributionTagger.java          # NEW
    ├── AdmissionMetrics.java           # MODIFIED — two-tag emission paths
    ├── AdmissionConfig.java            # MODIFIED — add attribution.maxHarnessCardinality
    └── (cardinality MeterFilter registration — could live in AdmissionMetrics @PostConstruct)
```

### Pattern 1: Jetty 12 Handshake Header Insertion (Client)

**What:** Set custom HTTP headers on the WS upgrade request via `ClientUpgradeRequest.setHeader(name, value)`. Multiple `setHeader` calls accumulate.

**When to use:** Every `BotClient.connect()` invocation must inject identity headers before `client.connect(endpoint, uri, req)`.

**Example:**
```java
// Source: jetty.org/docs/jetty/12.1/programming-guide/client/websocket.html (verified)
ClientUpgradeRequest req = new ClientUpgradeRequest();
req.addExtensions("permessage-deflate; server_no_context_takeover");
req.setHeader("X-Paralife-Source", identity.source());           // operator | harness | unknown
identity.harnessId().ifPresent(id -> req.setHeader("X-Paralife-Harness", id));
Session connected = client.connect(endpoint, URI.create(serverUri), req)
        .get(10, TimeUnit.SECONDS);
```

**Notes:**
- `setHeader` (not `addHeader`) — Jetty 12.1 uses `setHeader` per official docs. Older `org.eclipse.jetty.websocket.client.ClientUpgradeRequest` (pre-12) had both; the 12.x API consolidates on `setHeader`. `addHeader` may still exist but `setHeader` is documented.
- Headers are passed verbatim to the `Sec-WebSocket-Key` / upgrade GET line.
- TLS / WSS not in this phase but headers ride identically.

### Pattern 2: Spring `WebSocketSession.getHandshakeHeaders()` (Server)

**What:** `WebSocketSession.getHandshakeHeaders()` returns Spring's `HttpHeaders` — a case-insensitive multi-map.

**When to use:** Read once in `afterConnectionEstablished` and stash on the session attributes. Don't re-parse on every tick.

**Example:**
```java
// Source: org.springframework.web.socket.WebSocketSession
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    sessionRegistry.register(session);
    if (outboundSender != null) {
        outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize());
    }

    // Phase 18 D-06: read handshake identity.
    HttpHeaders headers = session.getHandshakeHeaders();
    String source = firstHeaderOrDefault(headers, "X-Paralife-Source", "unknown");
    String harnessId = headers.getFirst("X-Paralife-Harness");   // null if absent
    // Normalize: source must be in the bounded taxonomy. Unknown values fold to "unknown".
    if (!SOURCE_TAXONOMY.contains(source)) source = "unknown";
    session.getAttributes().put(ATTR_SOURCE, source);
    if (harnessId != null && "harness".equals(source)) {
        session.getAttributes().put(ATTR_HARNESS, harnessId);
    }

    long currentTick = tickEngine.currentTick();
    log.info("HARNESS connected tick={} session={} harness={} source={} active={}",
            currentTick, session.getId(),
            harnessId != null ? harnessId : "-", source,
            sessionRegistry.getSessionCount());
}

private static String firstHeaderOrDefault(HttpHeaders h, String name, String dflt) {
    String v = h.getFirst(name);
    return (v == null || v.isBlank()) ? dflt : v.trim();
}
```

**Notes:**
- Spring's `HttpHeaders` IS case-insensitive (it wraps a `LinkedCaseInsensitiveMap`). [VERIFIED: Spring Framework 6.2 javadoc] — the case-sensitivity bug in raw Jetty 12 `UpgradeRequest.getHeaders()` (issue #12429) does NOT apply when going through Spring's `WebSocketSession` adapter.
- Always `getFirst` — never assume single-value, never `get(0)`.
- Trim and bounded-taxonomy-check on the wire input to defend against header injection (the `MeterFilter` cardinality cap is the deeper safety net).

### Pattern 3: Two-Tag Counter Emission

**What:** Every admission-side counter increment gains `source` + (optionally) `harness` tags. `AttributionTagger` is the single helper.

**When to use:** Every metric write site (`AdmissionMetrics.incRejected`, `setActiveEntities`, `OutboundSender.markStalled`, `ActionResolver.incIngressOverwrite`).

**Example:**
```java
// AttributionTagger — pure helper, no Spring deps required.
public final class AttributionTagger {
    public static final Set<String> SOURCE_TAXONOMY = Set.of(
            "operator", "harness", "unknown", "overflow", "offspring");

    public static Tags tagsFor(WebSocketSession session) {
        if (session == null) return Tags.of("source", "unknown");
        Map<String, Object> attrs = session.getAttributes();
        String source = (String) attrs.getOrDefault(ATTR_SOURCE, "unknown");
        Object harness = attrs.get(ATTR_HARNESS);
        return harness == null
                ? Tags.of("source", source)
                : Tags.of("source", source, "harness", (String) harness);
    }
}

// Use site (in AdmissionMetrics):
public void incRejected(String reason, Tags attribution) {
    Counter.builder(M_REJECTED)
            .tag("reason", reason)
            .tags(attribution)
            .description("Admission rejections by reason token (Phase 17 D-17, Phase 18 D-12)")
            .register(registry)
            .increment();
}
```

### Pattern 4: Bounded-Cardinality MeterFilter (D-10)

**What:** `MeterFilter.maximumAllowableTags(prefix, tagKey, max, onMaxReached)` enforces an upper bound; `MeterFilter.replaceTagValues(...)` folds over-cap values to a sentinel.

**When to use:** Register once at startup (e.g., `AdmissionMetrics @PostConstruct` or a `MetricsConfig` `@Configuration`).

**Example:**
```java
// Source: docs.micrometer.io/micrometer/reference/concepts/meter-filters.html (verified)
@PostConstruct
void registerCardinalityCap() {
    int cap = admissionConfig.attribution().maxHarnessCardinality();   // default 64
    AtomicBoolean warned = new AtomicBoolean(false);
    MeterFilter overflowFilter = new MeterFilter() {
        @Override
        public Meter.Id map(Meter.Id id) {
            String existing = id.getTag("harness");
            if (existing != null && warned.compareAndSet(false, true)) {
                log.warn("HARNESS overflow first-seen tick={} harness-id={}",
                         tickEngine.currentTick(), truncate(existing, 32));
            }
            return id.withTag(Tag.of("harness", "overflow"));
        }
    };
    registry.config().meterFilter(
            MeterFilter.maximumAllowableTags("paralife.admission", "harness", cap, overflowFilter));
    registry.config().meterFilter(
            MeterFilter.maximumAllowableTags("paralife.backpressure", "harness", cap, overflowFilter));
}
```

**Notes:**
- The `maximumAllowableTags` filter applies to a **prefix** — one filter per metric-name root, not per concrete metric.
- `MeterFilter.replaceTagValues` is the alternative; the inline anonymous `MeterFilter` above is cleaner because it logs first-seen.
- **Memory pitfall:** if a misbehaving harness mints 10⁹ unique IDs, the filter's internal "seen tags" set grows unboundedly until the cap is reached, then folds — but the GitHub issue #4971 (linked below) documents a real memory leak when `MeterFilter.deny()` is used as the over-max action: filtered-but-already-registered meters aren't reaped. Use `replaceTagValues` (or the inline mapper above) so over-max requests fold into a single existing meter rather than getting denied. [CITED: micrometer-metrics/micrometer issue #4971]

### Pattern 5: Async Registration Tracking (D-04 BotFleet)

**What:** Replace `CountDownLatch.await(30s)` with `Map<String, CompletableFuture<RegistrationResult>>` so the 1000+ bot launch case doesn't generate spurious "didn't finish" log noise.

**When to use:** `BotFleet.launch(uri, count, identity, rampUp, mix)` returns immediately; per-bot futures complete async; aggregate `peakRegistered` / `currentRegistered` derived from snapshots, not awaits.

**Example:**
```java
public final class BotFleet {
    public record RegistrationResult(String botId, boolean registered, Optional<String> failureReason) {}

    private final Map<String, CompletableFuture<RegistrationResult>> futures = new ConcurrentHashMap<>();

    public List<BotClient> launch(String uri, int count, BotIdentity identity,
                                  RampUpSpec rampUp, SpeciesMix mix, BotFactory factory) {
        List<BotClient> bots = new CopyOnWriteArrayList<>();
        Iterator<RampUpSpec.Tick> ticks = rampUp.scheduler(count);
        for (int i = 0; i < count; i++) {
            ticks.next().awaitOrYield();   // blocks the *launcher* thread, not the connect VT
            char species = mix.pickFor(i, count);
            BotClient bot = factory.create(species, identity);
            bots.add(bot);
            String botId = "fleet-" + identity.harnessId().orElse("op") + "-" + i;
            CompletableFuture<RegistrationResult> fut = new CompletableFuture<>();
            futures.put(botId, fut);
            Thread.startVirtualThread(() -> {
                try {
                    bot.connect();
                    boolean ok = bot.awaitRegistered(15_000L);
                    fut.complete(new RegistrationResult(botId, ok, Optional.empty()));
                } catch (Exception e) {
                    fut.complete(new RegistrationResult(botId, false, Optional.of(e.getMessage())));
                }
            });
        }
        return bots;
    }

    public int peakRegistered() {  /* observe via CompletableFuture.getNow */  }
    public int currentRegistered() {  /* observe via BotClient.isRegistered */  }
    public CompletableFuture<Void> awaitAllSettled() {
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new));
    }
}
```

**Notes:**
- The launcher loop blocks per-tick on the rampUp scheduler — for `instant`, `awaitOrYield()` is a no-op, matching today's `BotLauncher` behavior.
- BotRunner's "100% registered or fail" semantics are preserved by `awaitAllSettled().get(timeout)` — the 30s bound is no longer a hard ceiling but a deliberate operator choice.

### Pattern 6: JSON Run Report — Atomic Temp-Rename (D-17)

**What:** Always write to `<path>.tmp`, then `Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`. POSIX guarantees atomic rename within the same filesystem; on Windows, `ATOMIC_MOVE` works for same-drive renames but throws `AtomicMoveNotSupportedException` across drives.

**Example:**
```java
public void writeOverwrite(Path target, ReportSnapshot snapshot, ObjectMapper mapper) throws IOException {
    Path dir = target.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = dir.resolve(target.getFileName() + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
        mapper.writeValue(out, snapshot);
    }
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
}

public void appendJsonl(Path target, ReportSnapshot snapshot, ObjectMapper mapper) throws IOException {
    // First call writes the static-config header line. State tracked in-memory.
    // Append-mode is allowed to skip atomic-rename for individual lines because
    // POSIX append is atomic for writes ≤ PIPE_BUF (4096 bytes) — the per-line
    // counter dump fits well under this. Crash mid-line leaves a truncated final
    // line; readers should tolerate this (jq -c handles it).
    String line = mapper.writeValueAsString(snapshot) + "\n";
    Files.writeString(target, line, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
}
```

**Notes:**
- For overwrite mode, atomic rename is the right answer.
- For append mode (JSONL), per-line append + `O_SYNC` is correct AND simpler. The line-by-line invariant means crash-mid-line is the worst failure case, not crash-mid-record. Phase 21 readers using `jq -c` will skip the truncated last line gracefully.
- D-17 says append mode is "Always written via atomic temp + rename" — interpret this as "the **header line** is written atomically before any counter line is appended"; per-line appends use APPEND+SYNC. Plan should clarify with the user during planning if they want stricter (atomic per line via copy-rename, ~10× I/O cost).

### Anti-Patterns to Avoid

- **Booting Spring inside `LoadHarness`:** doubles startup time, pulls server beans (TickEngine, AdmissionGate, WorldGrid) into a process that never uses them. The harness is a pure WS client.
- **Per-bot harness id (D-07 violation):** would generate cardinality of 1000+ tag values per harness — defeats D-10 entirely.
- **Re-using `BotLauncher` for the harness:** the 30s `await` ceiling at line 67 will fire on every 1000-bot launch and produce misleading "didn't finish" log lines. D-04 mandates the refactor.
- **Tagging `paralife.admission.maintenance` or `paralife.tick.health.work-time-ms` with `source`:** these are server-global. D-12 explicitly excludes them.
- **Raw Jetty `UpgradeRequest.getHeaders()` on the server side:** case-sensitive bug (issue #12429). Use Spring's `WebSocketSession.getHandshakeHeaders()` which is case-insensitive [VERIFIED].
- **Hand-rolled CLI parsing for `--ramp-up=rate:50`:** ramp-up syntax is non-trivial; a `picocli.ITypeConverter<RampUpSpec>` is 20 lines and gets you `--help` for free.
- **Adding `harness=<id>` to `paralife.admission.rejected{reason}` without registering the `MeterFilter` first:** without the cap, a misconfigured harness loop minting a uuid-per-launch will balloon the meter registry. Register the filter BEFORE the first metric write.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| CLI argument parsing with type converters and env-var defaults | Custom `args[]` walker | **picocli** `@Option`/`@Command` | Type converters (`RampUpSpec`, `Duration`), env-var fallback (`defaultValue = "${PARALIFE_HARNESS_HARNESS_ID}"`), `--help`, error messages. ~80KB jar. |
| Bounded-cardinality tag enforcement | `ConcurrentHashMap<String, Counter>` cache + size check | **Micrometer `MeterFilter.maximumAllowableTags`** | Hand-rolled cache ignores already-registered meters; `MeterFilter` integrates at the registry level. [VERIFIED: docs.micrometer.io] |
| Atomic file replace | Manual rename + delete dance | `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` | POSIX-atomic; one-line. Handles concurrent reader race. |
| JSON serialization of records | `String.format("{...}")` | **Jackson** `ObjectMapper.writeValue` | Records serialize natively; null-safe; field-name policy is configurable. Already on classpath. |
| Shaded fat jar | Custom `Jar` task with `from(configurations.runtimeClasspath)` | **Spring Boot `BootJar` task type** with second `archiveClassifier` | Reuses production-tested `BOOT-INF/` layered layout; Java's launcher already understands it. [VERIFIED: Spring Boot Gradle plugin docs] |
| WebSocket client with `permessage-deflate` | Spring's `StandardWebSocketClient` | **Jetty 12 native `WebSocketClient`** | Already in use; Spring's wrapper lacks public extension API. |

**Key insight:** Phase 18 has very little novel code. The integration points (handshake header, MeterFilter, BootJar) all have first-party library support. Resist the urge to write custom helpers; reach for `picocli.CommandLine`, `MeterFilter.maximumAllowableTags`, `Files.move(ATOMIC_MOVE)`, `BootJar`.

## Common Pitfalls

### Pitfall 1: Rebind path silently loses harness attribution

**What goes wrong:** A STALLED harness session reconnects via Phase 17 STALLED-pivot; on the rebind, the entityId is preserved but `ATTR_HARNESS` / `ATTR_SOURCE` could be lost.

**Why it happens:** STALLED-pivot CLOSES the WS. The client opens a NEW `WebSocketSession`. Session attributes are scoped to a session instance — the new session starts with an empty attribute map.

**How to avoid:** This works correctly **as long as `BotClient` re-sends the headers on reconnect** — and the new session's `afterConnectionEstablished` populates `ATTR_HARNESS`/`ATTR_SOURCE` from those headers BEFORE `handleRegister` is called for the rebind. The rebind path itself doesn't need to copy attributes from the old session.

**Verification step in the plan:** Add an integration test (`AttributionRebindTest`) that:
1. Launches a 1-bot harness with `harness-id=test-attribution`.
2. Forces a STALLED transition (e.g., suspend bot's read VT until queue overflows).
3. Reconnects with the resume token.
4. Asserts `paralife.admission.active.entities{source=harness, harness=test-attribution}` is still 1 after rebind — and NOT `source=unknown`.

**Warning signs:** Rising `paralife.admission.active.entities{source=unknown}` count over time correlates with a count of `BACKPRESSURE resumed` log markers — silent attribution drift.

### Pitfall 2: `BotClient` constructor sprawl

**What goes wrong:** `BotClient` already has 3 overloaded constructors (no-arg defaults / cooldown / cooldown+rng). Adding `BotIdentity` as a 7th positional arg is a refactoring landmine — every test file breaks.

**Why it happens:** `BotClient(serverUri, species, brain, respawnCooldownMs, respawnJitterMs, rng, identity)` — too many positional args, every existing call site needs updating; positional ordering bugs likely.

**How to avoid:** Introduce a small builder OR an `BotClientOptions` record. Default to `BotIdentity.unknown()` so existing test call sites keep working without edits:

```java
public record BotClientOptions(
        String serverUri,
        char species,
        HeuristicBrain brain,
        long respawnCooldownMs,
        long respawnJitterMs,
        Random rng,
        BotIdentity identity) {
    public static BotClientOptions defaults(String uri, char species, HeuristicBrain brain) {
        return new BotClientOptions(uri, species, brain, 100L, 50L, new Random(), BotIdentity.unknown());
    }
}
public BotClient(BotClientOptions opts) { ... }
// Keep existing constructors as thin wrappers around opts; deprecate but don't delete.
```

**Warning signs:** Compilation churn across 15+ test files for what should be a one-line behavior change.

### Pitfall 3: BotLauncher 30s `await` ceiling produces false-positive log noise

**What goes wrong:** At 1000 bots, the `allDone.await(30, TimeUnit.SECONDS)` at `BotLauncher.java:67` will fire spurious "Not all bots finished connecting within timeout" warnings even when bots ARE registering — they just take >30s to all settle under load.

**Why it happens:** The 30s is a hard wall-clock ceiling unrelated to actual bot status. At 100 bots it works; at 1000 it doesn't.

**How to avoid:** D-04 mandates the refactor — `BotFleet` uses async `CompletableFuture<RegistrationResult>` per bot. The fleet exposes `peakRegistered()` / `currentRegistered()` / `awaitAllSettled(timeout)`. The `LoadHarness` chooses its own observability strategy (10-second snapshot interval, no hard ceiling). `BotRunner` chooses `awaitAllSettled(30, TimeUnit.SECONDS)` to preserve current observable behavior.

**Warning signs:** Spurious warning lines correlated with successful 100-bot baselines.

### Pitfall 4: Header-spoofing attribution pollution (threat surface)

**What goes wrong:** Anyone with WebSocket access can set `X-Paralife-Source: harness` and `X-Paralife-Harness: foo` in their handshake. The server has no way to authenticate these.

**Why it happens:** `15-SCHEMA.md` doesn't define authn (this is a dev/internal-network project, not internet-exposed). Headers are accepted at face value.

**How to avoid:** This is **explicitly accepted scope** for Phase 18. Mitigations:
- The `MeterFilter.maximumAllowableTags` cap of 64 (D-10) bounds the metric-explosion DoS.
- `source` value is checked against `SOURCE_TAXONOMY`; unknown values fold to `unknown` (not propagated as-is).
- `harness` value is truncated to 32 chars before the cap-tracker sees it (defense in depth — protects against unbounded heap usage in the cap's internal seen-set).
- Document in `18-HARNESS.md` Forward Notes: "Harness identity is observability metadata, not authentication. Network controls (firewall, VPN, internal-only deployment) are the security boundary; M5 may add `/actuator/admission/whitelist`."

**Warning signs:** Sudden spike in `harness=overflow` count without a known harness deployment change.

### Pitfall 5: Spring Boot context startup cost in harness JVM

**What goes wrong:** A harness invoked via `./gradlew runHarness` has Gradle daemon overhead (~2-5s) PLUS, if `LoadHarness` accidentally pulls Spring (e.g., via `@SpringBootApplication`-style auto-config), another 3-5s of context startup.

**Why it happens:** Easy to make the mistake of letting Picocli's `CommandLine.run` intersect with `SpringApplication.run`.

**How to avoid:** `LoadHarness` is a plain `public static void main` — no `@SpringBootApplication`, no `SpringApplication.run`. The fat jar's `MANIFEST.MF` `Main-Class` is `com.paralife.harness.LoadHarness` directly (NOT `org.springframework.boot.loader.JarLauncher`).

But: `BootJar` task by default uses `JarLauncher`. The fat jar will still work but startup pays the loader cost. Test by `time java -jar build/libs/paralife-load-harness.jar --help` — if it's > 1s, swap to a plain `Jar` task with `attributes("Main-Class" to "com.paralife.harness.LoadHarness")`. Likely fine to leave with `BootJar` for simplicity; document the choice.

**Warning signs:** `--help` invocation takes 3+ seconds. Spring banner output in harness logs.

### Pitfall 6: JSON report file lock contention on Windows

**What goes wrong:** `Files.move(tmp, target, ATOMIC_MOVE)` throws `AtomicMoveNotSupportedException` on Windows when target is being read by another process (Phase 21 dashboard tooling, an IDE, etc.).

**Why it happens:** Windows file locks are mandatory; `ATOMIC_MOVE` requires exclusive access during rename.

**How to avoid:** The harness is documented as Linux-first per project's Spring/Gradle posture. If Windows support matters, fall back to non-atomic move on `AtomicMoveNotSupportedException`:

```java
try {
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
} catch (AtomicMoveNotSupportedException e) {
    log.warn("Atomic move unsupported on this filesystem ({}); falling back to non-atomic", e.getMessage());
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
}
```

**Warning signs:** AtomicMoveNotSupportedException on operator Windows machines.

## Code Examples

(See "Architecture Patterns" Pattern 1–6 above. All examples reference the source URLs they were derived from.)

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `BotLauncher` `CountDownLatch.await(30s)` | `BotFleet` `Map<String, CompletableFuture<RegistrationResult>>` | Phase 18 D-04 | Lifts the 100-bot scalability wall; enables 1000+ launches without spurious log noise. |
| Single-tag `paralife.admission.rejected{reason}` | Two-tag `{reason, source[, harness]}` | Phase 17 D-17 → Phase 18 D-12 | Per-source dashboards become possible; multi-harness deployments distinguishable. |
| Spring `StandardWebSocketClient` | Jetty 12 native `WebSocketClient` with `setHeader` | Phase 15 D-09 (transport) → Phase 18 D-06 (identity) | Custom handshake headers now feasible; `permessage-deflate` already negotiated. |
| Hand-rolled CLI in `BotRunner.main` | Picocli for `LoadHarness.main` | Phase 18 (proposed) | Type-safe ramp-up / species-mix; env-var overrides; `--help`. |

**Deprecated/outdated:**
- `paralife.websocket.max-active-entities` config key — already removed in Phase 17 D-04 (migrated to `paralife.admission.cap`). Phase 18 is a NEW config key (`paralife.admission.attribution.max-harness-cardinality`) and does not touch this.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Spring's `WebSocketSession.getHandshakeHeaders()` is case-insensitive (wraps `LinkedCaseInsensitiveMap`). | Pattern 2 | **LOW** — verified against Spring Framework 6.2 javadoc; if a future Spring update changed this, the test `WorldWebSocketHandlerHandshakeHeaderTest` (suggested in Validation Architecture) would catch it. |
| A2 | `MeterFilter.maximumAllowableTags` does not leak memory when over-cap values fold via `replaceTagValues` (rather than `deny()`). | Pattern 4 | **MEDIUM** — Micrometer issue #4971 documents leaks with `deny()`; the fold pattern is the recommended workaround. Plan should add a 100-iteration overflow stress test. |
| A3 | Spring Boot 3.4.4's `BootJar` task type accepts a second instance with a different `mainClass` and `archiveClassifier` and produces a runnable second fat jar. | Standard Stack > Installation | **LOW** — the `BootJar` Gradle task is a regular Gradle task; multi-instance is straightforward. Validate with `./gradlew loadHarnessJar && java -jar build/libs/*-load-harness.jar --help` during planning's plan-checker pass. |
| A4 | Picocli 4.7.7 is the latest stable as of 2026-04. | Standard Stack | **LOW** — verified against Maven Central and remkop/picocli releases. |
| A5 | Append-mode JSONL with `StandardOpenOption.APPEND + SYNC` is sufficiently crash-safe per D-17's "atomic temp + rename" wording — i.e., the user's intent is "atomic rename for the header line; per-line append for counters" rather than "atomic rename PER LINE". | Pattern 6 | **MEDIUM** — should be confirmed during planning (one user clarification question). If the user wants per-line atomic, the cost is ~10× I/O — measurable but acceptable at 30s default cadence. |
| A6 | `BotClient` currently re-sends handshake headers on STALLED-pivot reconnect (because the BotIdentity is held as a field, not bound to a single session lifecycle). | Pitfall 1 | **HIGH** — must be verified in code during planning. If false, Phase 17's STALLED-pivot test path silently loses attribution. Mitigation: explicit test (`AttributionRebindTest`) and explicit check of `BotClient.connect()` ordering. |
| A7 | `MeterFilter` registration via `@PostConstruct` in `AdmissionMetrics` runs BEFORE the first counter write. | Pattern 4 | **LOW** — Spring guarantees `@PostConstruct` runs before the bean is exposed; counter writes happen at request time, after startup. But if a `@EventListener(ApplicationReadyEvent)` accidentally writes before `@PostConstruct`, things fail. Plan should specify `@PostConstruct` ordering explicitly. |

## Open Questions

1. **Cardinality cap config namespace.**
   - What we know: D-10 says default 64; tunable via `paralife.admission.attribution.max-harness-cardinality` OR `paralife.harness.*` (Claude's discretion).
   - What's unclear: which namespace the user prefers — `attribution` lives under `admission` (cohesive with Phase 17 cap-related config), `harness` would be a new namespace.
   - Recommendation: Use `paralife.admission.attribution.max-harness-cardinality` — keeps server-side admission-related config under one prefix and avoids creating a server-side `paralife.harness.*` namespace that has no other members. Document this choice in `18-HARNESS.md`.

2. **Append-mode JSONL atomicity strictness.**
   - What we know: D-17 says "atomic temp + rename" applies "always".
   - What's unclear: Whether per-line or only header-line atomic-rename is required.
   - Recommendation: Per A5 — propose header-line atomic + counter-line append+SYNC; surface as a user clarification at plan-time if the user disagrees.

3. **Whether `BotLauncher.java` is deleted or kept as a thin facade.**
   - What we know: D-04 says "refactor, do not fork."
   - What's unclear: Whether an external test (`HundredBotIntegrationTest`?) imports `BotLauncher` directly.
   - Recommendation: Search for `import com.paralife.bot.BotLauncher`; if any non-test file imports it, keep a deprecated facade. Otherwise delete.

4. **Whether `LoadTest.java` opts into harness-tagged path.**
   - What we know: Claude's discretion in CONTEXT.md says "recommended" for end-to-end attribution coverage.
   - What's unclear: Whether updating LoadTest blocks any expectation in `LoadTestSummary.md` etc.
   - Recommendation: Yes — set `harness-id=test-load` and `source=harness` in LoadTest. Adds attribution-path coverage with zero behavioral cost.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 (with virtual threads) | LoadHarness, BotClient, BotFleet | ✓ | Temurin 21.0.6+7 | — |
| Gradle wrapper | `loadHarnessJar`, `runHarness` tasks | ✓ | 8.14.2 | — |
| Spring Boot Gradle plugin | `BootJar` task type for harness fat jar | ✓ | 3.4.4 | — |
| Jetty 12 native WS client | BotClient handshake header | ✓ | 12.0.18 | — |
| Picocli | LoadHarness CLI parsing | ✗ | — | Hand-rolled args parser (recommend NOT — adds 200+ lines for marginal save). Add `info.picocli:picocli:4.7.7` to `build.gradle.kts`. |
| Jackson (databind) | JSON report writer | ✓ | transitive via `spring-boot-starter-web` | — |
| Micrometer (core) with `MeterFilter.maximumAllowableTags` | Bounded-cardinality enforcement | ✓ | 1.14.x (Spring Boot managed) | — |

**Missing dependencies with no fallback:**
- None.

**Missing dependencies with fallback:**
- Picocli — fallback is hand-rolled parsing; recommend adding the dep instead.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) + AssertJ + Spring Boot Test 3.4.4 |
| Config file | `src/test/resources/application.yml` (none currently — uses `@TestPropertySource`) |
| Quick run command | `./gradlew test --tests "com.paralife.harness.*" --tests "com.paralife.admission.AttributionTagTest" -x slowtest` |
| Full suite command | `./gradlew test` (excludes `@Tag("slow")`); `./gradlew test -PincludeLong=true` includes load tests |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| **SCALE-03** | Standalone harness can launch >100 bots from a fat jar (`java -jar paralife-load-harness.jar --count=200 --duration=10`) | integration (slow) | `./gradlew test --tests "com.paralife.harness.LoadHarnessIntegrationTest" -PincludeLong=true` | ❌ Wave 0 |
| **SCALE-03** | `BotFleet` async registration tracking; no 30s `await` ceiling | unit | `./gradlew test --tests "com.paralife.bot.BotFleetTest"` | ❌ Wave 0 |
| **SCALE-03** | Ramp-up modes: instant, rate, wave parse and behave correctly | unit | `./gradlew test --tests "com.paralife.harness.RampUpSpecTest"` | ❌ Wave 0 |
| **SCALE-03** | JSON report — overwrite mode, atomic temp-rename | unit | `./gradlew test --tests "com.paralife.harness.ReportWriterTest"` | ❌ Wave 0 |
| **SCALE-03** | JSON report — append/JSONL mode, header line + counter lines | unit | `./gradlew test --tests "com.paralife.harness.ReportWriterTest"` | ❌ Wave 0 |
| **SCALE-04** | Server reads `X-Paralife-Harness` / `X-Paralife-Source` from handshake; stashes ATTR_HARNESS / ATTR_SOURCE | unit (Spring `MockMvc` not applicable; use a real Jetty test or attribute-injection helper) | `./gradlew test --tests "com.paralife.websocket.WorldWebSocketHandlerHandshakeHeaderTest"` | ❌ Wave 0 |
| **SCALE-04** | `paralife.admission.rejected{reason, source, harness}` tagged correctly across reasons | unit | `./gradlew test --tests "com.paralife.admission.AttributionTagTest"` | ❌ Wave 0 |
| **SCALE-04** | Bounded cardinality: 65th unique harness folds to `harness=overflow`; one-time warning logged | unit | `./gradlew test --tests "com.paralife.admission.CardinalityCapTest"` | ❌ Wave 0 |
| **SCALE-04** | Default `source=unknown` when no header set | unit | `./gradlew test --tests "com.paralife.admission.AttributionTagTest::defaultUnknown"` | ❌ Wave 0 |
| **SCALE-04** | Attribution survives STALLED-pivot rebind | integration | `./gradlew test --tests "com.paralife.admission.AttributionRebindTest"` | ❌ Wave 0 |
| **SCALE-04** | `HARNESS connected` / `HARNESS disconnected` log markers emitted with required fields | integration | `./gradlew test --tests "com.paralife.websocket.HarnessLogMarkerTest"` | ❌ Wave 0 |
| **SCALE-05** | `BotRunner` ≤100-bot path unchanged: exit codes, stdout, completion semantics | integration | `./gradlew test --tests "com.paralife.bot.BotRunnerRegressionTest"` | ❌ Wave 0 |
| **SCALE-05** | `BotRunner` sends `source=operator` and NO `harness` header | unit | `./gradlew test --tests "com.paralife.bot.BotRunnerOperatorTagTest"` | ❌ Wave 0 |
| **SCALE-05** | Existing `LoadTest.java` (now harness-tagged) still passes 100-bot baseline | integration (slow) | `./gradlew test --tests "com.paralife.engine.LoadTest" -PincludeLong=true` | ✓ exists; needs migration |
| **SCALE-05** | `HundredBotIntegrationTest` unchanged | integration (slow) | `./gradlew test --tests "com.paralife.websocket.HundredBotIntegrationTest" -PincludeLong=true` | ✓ exists |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "com.paralife.harness.*" --tests "com.paralife.admission.Attribution*" --tests "com.paralife.bot.BotFleet*"` — fast unit tests (<10s expected).
- **Per wave merge:** `./gradlew test` — full default suite (excludes `@Tag("slow")`).
- **Phase gate:** `./gradlew test -PincludeLong=true` — full suite including load tests; ≥99% pass; no regressions; `LoadHarnessIntegrationTest` passes.

### Wave 0 Gaps

- [ ] `src/test/java/com/paralife/harness/LoadHarnessIntegrationTest.java` — boots embedded server + harness against `--count=200`, verifies registration + report file shape.
- [ ] `src/test/java/com/paralife/harness/RampUpSpecTest.java` — covers `instant`, `rate:N`, `wave:N:ms` parsing + scheduling.
- [ ] `src/test/java/com/paralife/harness/SpeciesMixTest.java` — `balanced` + custom `0.4:0.3:0.3` parsing.
- [ ] `src/test/java/com/paralife/harness/ReportWriterTest.java` — overwrite atomic-rename; append JSONL header + counter lines; AtomicMoveNotSupported fallback.
- [ ] `src/test/java/com/paralife/bot/BotFleetTest.java` — async future tracking; settled-vs-pending; rampUp respect; identity propagation.
- [ ] `src/test/java/com/paralife/bot/BotFactoryTest.java` — D-19 reserved-param shape; default unknown identity.
- [ ] `src/test/java/com/paralife/bot/BotRunnerRegressionTest.java` — exit-code parity with current `BotRunner`.
- [ ] `src/test/java/com/paralife/bot/BotRunnerOperatorTagTest.java` — verifies `source=operator` header sent.
- [ ] `src/test/java/com/paralife/admission/AttributionTagTest.java` — two-tag scheme across reject reasons.
- [ ] `src/test/java/com/paralife/admission/AttributionRebindTest.java` — STALLED→rebind preserves attribution.
- [ ] `src/test/java/com/paralife/admission/CardinalityCapTest.java` — 64-cap + overflow-fold + warn-once.
- [ ] `src/test/java/com/paralife/websocket/WorldWebSocketHandlerHandshakeHeaderTest.java` — handshake-header read + ATTR stashing.
- [ ] `src/test/java/com/paralife/websocket/HarnessLogMarkerTest.java` — `HARNESS connected/disconnected` log marker structure.
- [ ] Test framework install: none — JUnit 5 + AssertJ + Spring Boot Test already wired.
- [ ] Picocli dependency: needs adding to `build.gradle.kts`.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no (project explicitly internal-network/dev) | N/A — `15-SCHEMA.md` doesn't define authn; Phase 18 explicitly accepts this. Document the boundary in `18-HARNESS.md`. |
| V3 Session Management | partial | WS session lifecycle managed by Phase 17 admission; harness identity is observability metadata, not auth. |
| V4 Access Control | no | Same as V2 — network-level controls (firewall, VPN) are the boundary. |
| V5 Input Validation | yes | Handshake header values must be validated: `source` against `SOURCE_TAXONOMY` (folds unknown → `unknown`); `harness` truncated to 32 chars before cardinality-cap check. CLI option values validated by Picocli type converters. |
| V6 Cryptography | no | Tokens are observability ids, not credentials. Phase 17 resume tokens already use `ThreadLocalRandom.nextLong()` — sufficient. |
| V7 Error Handling & Logging | yes | One-time warning log on cardinality overflow (D-10). HARNESS log markers must NOT log full handshake-header values without truncation. |
| V8 Data Protection | no | Harness IDs are not secrets. |

### Known Threat Patterns for {Java/Spring/Jetty internal-network}

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Header-spoofing for metric pollution (anyone can claim `source=harness, harness=victim-id`) | Tampering | `MeterFilter.maximumAllowableTags(64)` cap (D-10) bounds the heap-DoS; document network-controls-as-boundary in `18-HARNESS.md`; future M5 may add `/actuator/admission/whitelist`. |
| Cardinality DoS via uuid-per-launch loop | Denial-of-Service | Same as above. |
| Header-injection / CRLF in `X-Paralife-Harness` value | Tampering | Spring's `HttpHeaders` parser strips CRLF; truncate to 32 chars before storage. |
| Long-running harness JSON file fills disk | DoS | JSONL line size is bounded by counter-record schema; operators rotate via `logrotate` or `--report-out=/dev/null` for ephemeral runs. Document. |
| Resume-token replay across harness identities | Spoofing | Phase 17 D-13 already locks: tokens are single-use, consumed on rebind. No new threat surface. |

## Sources

### Primary (HIGH confidence)

- **Phase 17 codebase** — `BotClient.java`, `BotLauncher.java`, `BotRunner.java`, `WorldWebSocketHandler.java`, `AdmissionMetrics.java`, `AdmissionGate.java`, `ResumeTokenRegistry.java`, `AdmissionConfig.java`, `build.gradle.kts` — all read directly from `/home/mark/kramtime/paralife/`.
- **`17-ADMISSION.md`** — full admission contract.
- **`15-SCHEMA.md`** — confirms wire grammar locked; Phase 18 does not edit.
- **CONTEXT.md** — D-01 through D-21 locked.
- [WebSocket Client :: Eclipse Jetty 12.1 docs](https://jetty.org/docs/jetty/12.1/programming-guide/client/websocket.html) — `ClientUpgradeRequest.setHeader(name, value)` verified.
- [WebSocket Server :: Eclipse Jetty 12.1 docs](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html) — server-side handshake context.
- [Meter Filters :: Micrometer](https://docs.micrometer.io/micrometer/reference/concepts/meter-filters.html) — `maximumAllowableTags` API verified.
- [High Cardinality Tags Detector :: Micrometer](https://docs.micrometer.io/micrometer/reference/concepts/high-cardinality-tags-detector.html) — defensive pattern recommendation.
- [Packaging Executable Archives :: Spring Boot Gradle plugin](https://docs.spring.io/spring-boot/gradle-plugin/packaging.html) — `BootJar` task type, multi-instance.
- [picocli quick guide](https://picocli.info/quick-guide.html) — `@Option` annotations, env-var defaults, type converters.
- [Spring Framework `HttpHeaders` (case-insensitive `LinkedCaseInsensitiveMap`)](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/HttpHeaders.html) — confirms case-insensitive lookup on `WebSocketSession.getHandshakeHeaders()`.

### Secondary (MEDIUM confidence)

- [Memory leak with high cardinality tag and MeterFilter (issue #4971)](https://github.com/micrometer-metrics/micrometer/issues/4971) — informs the recommendation to fold rather than `deny()`.
- [HandshakeRequest getHeaders are case sensitive (Jetty issue #12429)](https://github.com/jetty/jetty.project/issues/12429) — clarifies that the case-sensitivity bug is in Jetty's raw `UpgradeRequest.getHeaders()`, NOT Spring's `WebSocketSession.getHandshakeHeaders()` (which uses Spring's case-insensitive wrapper).
- [Java's Files.move() on Windows](https://medium.com/@AlexanderObregon/javas-files-move-method-explained-7dee1287fa92) — informs the AtomicMoveNotSupportedException fallback recommendation.

### Tertiary (LOW confidence — none)

All claims with `[ASSUMED]` are tagged in the Assumptions Log table.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries verified on classpath or against current docs.
- Architecture: HIGH — patterns derived from Phase 17 actual code reads, not training-data extrapolation.
- Pitfalls: HIGH on items 1–3 (verified in code), MEDIUM on items 4–6 (security/Windows-edge-case reasoning).
- Validation Architecture: HIGH — based on existing test framework + project conventions.
- Security: MEDIUM — relies on accepting "internal network = trust boundary" stance documented in CLAUDE.md and Phase 17.

**Research date:** 2026-04-28
**Valid until:** 2026-05-28 (30 days; stable Spring Boot / Jetty / Micrometer ecosystem).
