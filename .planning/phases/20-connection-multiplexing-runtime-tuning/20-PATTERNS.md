# Phase 20: Connection Multiplexing & Runtime Tuning - Pattern Map

**Mapped:** 2026-05-09
**Files analyzed:** 17 (5 new code, 5 new docs/profile artifacts, 7 modified)
**Analogs found:** 14 / 17 (3 are genuinely novel — profile artifacts, profiler bootstrap, profile-dir README)

---

## File Classification

| New / Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `src/main/java/com/paralife/runtime/JettyRuntimeConfig.java` (NEW) | `@ConfigurationProperties` record | config-binding | `src/main/java/com/paralife/admission/AdmissionConfig.java:22-46,113-148` | exact |
| `src/main/java/com/paralife/runtime/AppRuntimeConfig.java` (NEW) | `@ConfigurationProperties` record (nested sub-records) | config-binding | `src/main/java/com/paralife/admission/AdmissionConfig.java` (full file — outer record + nested `BackpressureConfig`) | exact |
| `src/main/java/com/paralife/runtime/RuntimeBeansConfig.java` (NEW) | `@Configuration` bean factory | bean-wiring | `src/main/java/com/paralife/admission/AdmissionBeansConfig.java` (entire file) | exact |
| `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java` (MODIFY) | Jetty customizer / `@Bean` factory | request-response (WS upgrade) | self (`JettyDeflateCustomizer.java:75-82`) | exact (extension of existing pattern) |
| `src/main/java/com/paralife/admission/OutboundSender.java` (MODIFY) | per-session VT loop service | streaming + event-driven | self — only docstring + inline-comment additions per D-02 | exact (pure documentation tweak) |
| `src/main/java/com/paralife/websocket/TickBroadcaster.java` (MODIFY) | frame encode hot path | streaming (broadcast) | self — codec opts and `AppRuntimeConfig` injection only | role-match (no other broadcaster) |
| `src/main/java/com/paralife/codec/PerceptionCodec.java` (MODIFY) | codec / static utility | transform | self — internal-constant tuning per D-10 | exact (own pattern, no public surface change) |
| `src/main/java/com/paralife/codec/Base64Codec.java` (MODIFY) | codec / static utility | transform | self / `PerceptionCodec.java` sibling | exact |
| `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (MODIFY) | WS handler (upgrade site) | event-driven | self — D-02 inline comment only (line 320 area) | exact (comment-only) |
| `src/main/resources/application.yml` (MODIFY) | YAML config defaults | config | self — existing `paralife.admission:` block at lines 48-59 | exact |
| `build.gradle.kts` (MODIFY) | Gradle build script | build | self — additive only (no pattern change; D-08 forbids wrappers) | partial |
| `CLAUDE.md` (MODIFY) | project instructions | doc | self — `§Outbound concurrency`, `§Connection model`, `§markStalled close-then-best-effort-OOB` subsection style | exact |
| `README.md` (MODIFY) | operator-facing doc | doc | currently 1 line; pattern borrowed from `CLAUDE.md` opening structure | partial (file is essentially empty) |
| `.planning/phases/20-.../20-RUNTIME.md` (NEW) | phase spec doc | doc | `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` + `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` | exact |
| `.planning/phases/20-.../profiles/README.md` (NEW) | profile-dir README | doc | none (genuinely novel — no `.planning/.../profiles/README.md` precedent) | NO ANALOG |
| `.planning/phases/20-.../profiles/*.jfr` + `*.html` + `*.meta.json` (NEW) | binary evidence artifacts | data | none (first phase to ship JFRs in-tree) | NO ANALOG |
| `tools/async-profiler-bootstrap.md` (NEW) | tool-bootstrap doc | doc | none (no `tools/` dir precedent in repo) | NO ANALOG |

---

## Pattern Assignments

### `src/main/java/com/paralife/runtime/JettyRuntimeConfig.java` — `@ConfigurationProperties` record

**Analog:** `src/main/java/com/paralife/admission/AdmissionConfig.java`

**Imports + class header pattern** (`AdmissionConfig.java:1-22`):

```java
package com.paralife.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Durable admission policy config (Phase 17, replaces {@code PopulationCapConfig}).
 *
 * <p>Bound to {@code paralife.admission.*} in {@code application.yml}.
 *
 * <p>Decisions:
 * <ul>
 *   <li>D-15: tick-overload watermarks live in config, not constants</li>
 * </ul>
 *
 * <p>See {@code .planning/phases/17-.../17-ADMISSION.md} for the full spec.
 */
@ConfigurationProperties(prefix = "paralife.admission")
public record AdmissionConfig(...
```

**Compact-constructor validation + defaults factory** (`AdmissionConfig.java:37-53`):

```java
@ConstructorBinding
public AdmissionConfig {
    if (cap <= 0) {
        throw new IllegalArgumentException(
                "paralife.admission.cap must be > 0 (got " + cap + ")");
    }
    if (tickOverload == null) tickOverload = TickOverloadConfig.defaults();
    ...
}

/** Convenience for tests that instantiate without Spring. */
public static AdmissionConfig defaults() {
    return new AdmissionConfig(DEFAULT_CAP, false, ...);
}
```

**Notes for planner:**
- Auto-discovered via `@ConfigurationPropertiesScan` on `ParalifeApplication.java:8` — no manual registration.
- Per D-09, every field gets a `[live-tunable | launch-only]` tag in its javadoc.
- Defaults must match Jetty defaults (4 KB buffers, 60 000 ms idle) so binding adds zero behavioural change at first boot.

---

### `src/main/java/com/paralife/runtime/AppRuntimeConfig.java` — nested-record `@ConfigurationProperties`

**Analog:** `src/main/java/com/paralife/admission/AdmissionConfig.java` (specifically the outer + `BackpressureConfig` nested-record shape)

**Nested-record pattern** (`AdmissionConfig.java:113-148`):

```java
public record BackpressureConfig(
        @DefaultValue("128") int outboundQueueSize,
        @DefaultValue("10") int graceWindowTicks) {

    @ConstructorBinding
    public BackpressureConfig {
        if (outboundQueueSize < 1) {
            throw new IllegalArgumentException(
                    "paralife.admission.backpressure.outbound-queue-size must be >= 1 (got "
                            + outboundQueueSize + ")");
        }
        ...
    }

    public static BackpressureConfig defaults() {
        return new BackpressureConfig(128, 10);
    }
}
```

**Notes for planner (per RESEARCH §Plan 3 + Open Question 2 resolution):**
- Single outer record with nested sub-records (`OutboundConfig`, `EncodeConfig`, etc.) is the recommended shape — mirrors `AdmissionConfig` → `BackpressureConfig` / `TickOverloadConfig` / `AttributionConfig`.
- Reserve a `parallel-encode-threshold` field (live-tunable, default = sentinel-disabled) for the Phase 19.1 follow-up — record-binding is forward-compatible.
- D-20 explicitly says: do **NOT** move `paralife.admission.backpressure.outbound-queue-size` into this record. Layer alongside.

---

### `src/main/java/com/paralife/runtime/RuntimeBeansConfig.java` — `@Configuration` bean factory

**Analog:** `src/main/java/com/paralife/admission/AdmissionBeansConfig.java`

**Whole-file template** (`AdmissionBeansConfig.java:1-30`):

```java
package com.paralife.admission;

import com.paralife.engine.TickEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring bean factory for Phase 18 attribution infrastructure.
 *
 * <p>Produces the {@link AttributionTagger} singleton, which cannot be a
 * {@code @Component} because its constructor takes non-bean parameters
 * ({@code maxCardinality} from config, optional {@link TickEngine} reference).
 */
@Configuration
public class AdmissionBeansConfig {

    @Bean
    public AttributionTagger attributionTagger(AdmissionConfig admissionConfig,
                                               TickEngine tickEngine) {
        return new AttributionTagger(
                admissionConfig.attribution().maxHarnessCardinality(),
                tickEngine);
    }
}
```

**Notes for planner:**
- Use this only if `AppRuntimeConfig` needs to wire non-bean primitives (e.g., a tuned-buffer pool with non-bean ctor args). If injecting the record straight into existing `@Component` services covers Plan 3, `RuntimeBeansConfig.java` may not be needed at all.
- Alternative: extend `JettyDeflateCustomizer` to accept `JettyRuntimeConfig` directly — keeps Jetty wiring co-located. Planner picks; both are project-idiomatic.

---

### `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java` (MODIFY)

**Analog:** itself (`JettyDeflateCustomizer.java:75-82`)

**Existing `@Bean` to extend** (`JettyDeflateCustomizer.java:60-82`):

```java
/**
 * <p>Idle timeout is raised from Jetty's 30s default to {@code idleTimeoutMs}
 * (default 60s) via {@link JettyRequestUpgradeStrategy#addWebSocketConfigurer}.
 */
@Bean
public JettyRequestUpgradeStrategy jettyRequestUpgradeStrategy(
        @Value("${paralife.websocket.idle-timeout-ms:60000}") long idleTimeoutMs) {
    JettyRequestUpgradeStrategy strategy = new JettyRequestUpgradeStrategy();
    Duration idleTimeout = Duration.ofMillis(idleTimeoutMs);
    strategy.addWebSocketConfigurer(c -> c.setIdleTimeout(idleTimeout));
    return strategy;
}
```

**Phase 20 mutation pattern** (chain new `Configurable` setters on the same `addWebSocketConfigurer` lambda; per RESEARCH §Pattern 2 — `Configurable` interface accepts `setIdleTimeout`, `setInputBufferSize`, `setOutputBufferSize`, `setMaxFrameSize`, `setMaxBinaryMessageSize`, `setMaxTextMessageSize`, `setAutoFragment`):

```java
@Bean
public JettyRequestUpgradeStrategy jettyRequestUpgradeStrategy(
        JettyRuntimeConfig runtimeConfig,
        @Value("${paralife.websocket.idle-timeout-ms:60000}") long legacyIdleTimeoutMs) {
    JettyRequestUpgradeStrategy strategy = new JettyRequestUpgradeStrategy();
    strategy.addWebSocketConfigurer(c -> {
        c.setIdleTimeout(Duration.ofMillis(runtimeConfig.idleTimeoutMs()));
        c.setInputBufferSize(runtimeConfig.inputBufferSize());
        c.setOutputBufferSize(runtimeConfig.outputBufferSize());
        c.setMaxFrameSize(runtimeConfig.maxFrameSize());
        c.setMaxBinaryMessageSize(runtimeConfig.maxBinaryMessageSize());
        c.setMaxTextMessageSize(runtimeConfig.maxTextMessageSize());
        c.setAutoFragment(runtimeConfig.autoFragment());
    });
    return strategy;
}
```

**Notes for planner:**
- Keep the legacy `paralife.websocket.idle-timeout-ms` `@Value` slot alive for one phase as a fallback so existing `@TestPropertySource` annotations don't silently break (RESEARCH "Migration note" — additive, not breaking).
- All seven `Configurable` setters are launch-only (Jetty applies them at WS upgrade) — tag accordingly in the `JettyRuntimeConfig` field javadocs.
- Existing `deflateEnforcementFilter()` `@Bean` (lines 84-97) is untouched.

---

### `src/main/java/com/paralife/admission/OutboundSender.java` (MODIFY — comments + injection only)

**Analog:** itself

**Inline-comment insertion site** (near `OutboundSender.java:132-135`):

```java
ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(queueCapacity);
queues.put(id, queue);
overflowFiredFlags.put(id, new AtomicBoolean(false));
Thread t = Thread.ofVirtual()
        .name("ws-sender-" + id)
        .start(() -> drainLoop(session, queue));
```

**Comment template (per RESEARCH §8 inline-comment templates — D-02 codification):**

```java
// Phase 20 D-02 — One drain VT per session is structural per the WS:entity 1:1
// model (CLAUDE.md §Connection model, 18-HARNESS.md §1, 20-RUNTIME.md §1).
// Per-VT cost is a few KB heap; 1000+ VTs is acceptable. Per-connection cost
// is reduced via paralife.runtime.* tuning (see 20-RUNTIME.md §3), NOT by
// sharing the drain VT across sessions. Multi-entity-per-VT requires explicit
// ADR per Phase 18 D-21.
Thread t = Thread.ofVirtual().name("ws-sender-" + id) ...
```

**Notes for planner:**
- The class javadoc at `OutboundSender.java:32-44` already contains the rationale narrative — Phase 20 cross-references it (does not duplicate). Add a one-line `@see 20-RUNTIME.md §1` to the class javadoc.
- D-10 anti-pattern guard from RESEARCH: do NOT move encode/metric work inside the `synchronized(session)` block — the existing layout (lines 286-289) is load-bearing.
- If `AppRuntimeConfig` exposes an overflow-watermark or queue-depth tunable, inject it via constructor — `OutboundSender` is already a `@Component` so Spring resolves automatically.

---

### `src/main/java/com/paralife/websocket/TickBroadcaster.java` (MODIFY)

**Analog:** itself (no other broadcaster in the repo); codec hot-path opts cite `OutboundSender.drainLoop` as the encode site (`OutboundSender.java:286-288`):

```java
String encoded = PerceptionCodec.encode(frame);
byte[] encodedBytes = encoded.getBytes(StandardCharsets.UTF_8);
metrics.recordFrameSize(encodedBytes.length);
```

**Notes for planner:**
- Phase 20 codec opts (D-10) are JFR-driven only — RESEARCH §Pattern 5 candidates: thread-local `StringBuilder` reuse (`PerceptionCodec.java:56` allocates a fresh `StringBuilder(128)` per encode), ASCII fast-path on `Position` encoding, allocation elimination in `encodeTick`.
- Constraint: no field added to `PerceptionCodec` may break `private PerceptionCodec()` "pure static; no hidden state" contract (`PerceptionCodec.java:9`) — a `ThreadLocal<StringBuilder>` is acceptable as a `private static final` field but the javadoc needs an exception note.
- Three-gate stack (D-11) must run in-suite for two consecutive greens per opt (RESEARCH Pitfall 4).

---

### `src/main/java/com/paralife/codec/PerceptionCodec.java` (MODIFY — D-10 hot-path opts)

**Analog:** itself

**Current encode entrypoint** (`PerceptionCodec.java:54-65`):

```java
public static String encode(Frame f) {
    if (f == null) throw new CodecException("Cannot encode null frame");
    StringBuilder sb = new StringBuilder(128);
    switch (f) {
        case Frame.TickFrame t -> encodeTick(sb, t);
        case Frame.SyncFrame s -> encodeSync(sb, s);
        case Frame.RegisterFrame r -> encodeRegister(sb, r);
        case Frame.ActionFrame a -> encodeAction(sb, a);
        case Frame.ErrorFrame e -> encodeError(sb, e);
    }
    return sb.toString();
}
```

**Notes for planner:**
- Initial-capacity `128` is conservative; JFR may show under-sized → grow allocations dominate. Tune from evidence per D-10.
- `15-SCHEMA.md` is LOCKED (CLAUDE.md / CONTEXT) — every codec opt must round-trip byte-for-byte through `GoldenTraceEquivalenceTest`. No wire-format mutation.
- `Base64Codec.java` table-lookup is already efficient (`INT_TO_CHAR` / `CHAR_TO_INT`) — opts likely focus on `PerceptionCodec` allocation.

---

### `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (MODIFY — D-02 inline comment)

**Analog:** itself

**Comment insertion site** (`WorldWebSocketHandler.java:317-321`):

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    sessionRegistry.register(session);
    if (outboundSender != null) {
        outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize());
    }
```

**Comment template (per RESEARCH §8 — D-02):**

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    sessionRegistry.register(session);
    if (outboundSender != null) {
        // Phase 20 D-02 — WS:entity 1:1 is a deliberate architectural choice, not an
        // optimisation gap. See 20-RUNTIME.md §1 (and 18-HARNESS.md §1, CLAUDE.md
        // §Connection model). Tuning per-connection cost is the equivalent
        // transport-level scale strategy (SCALE-08); collapsing entities onto a shared
        // session would require explicit ADR per D-21 of Phase 18.
        outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize());
    }
```

**Notes for planner:**
- Comment-only change; no behavioural diff.
- The reference to `admissionConfig.backpressure().outboundQueueSize()` stays put (D-20 — alongside-not-move).

---

### `src/main/resources/application.yml` (MODIFY)

**Analog:** itself — existing `paralife.admission:` block at lines 48-59 is the closest precedent.

**Existing block to mirror** (`application.yml:48-59`):

```yaml
paralife:
  admission:
    cap: 256
    maintenance: false
    tick-overload:
      high-water-pct: 80
      low-water-pct: 60
      window-ticks: 10
    backpressure:
      outbound-queue-size: 128
      grace-window-ticks: 10
    attribution:
      max-harness-cardinality: 64
```

**Phase 20 additions land at `paralife.runtime.{jetty,app}` (per RESEARCH plans 2 + 3). Per D-09, each field carries a `[live-tunable | launch-only]` comment:**

```yaml
paralife:
  runtime:
    jetty:
      # All [launch-only] — applied by JettyRequestUpgradeStrategy.addWebSocketConfigurer
      input-buffer-size: 4096      # Jetty default
      output-buffer-size: 4096     # Jetty default
      max-frame-size: 65536
      max-binary-message-size: 65536
      max-text-message-size: 65536
      idle-timeout-ms: 60000       # also exposed for legacy paralife.websocket.idle-timeout-ms compat
      auto-fragment: true
    app:
      # nested sub-records — see AppRuntimeConfig
      outbound:
        # [live-tunable | launch-only] — to be tagged per field
      encode:
        # parallel-encode-threshold reserved for Phase 19.1
```

**Notes for planner:**
- D-20 confirms `paralife.admission.backpressure.outbound-queue-size` stays put — do NOT delete or rename it.
- Defaults must reproduce Jetty defaults so a no-op upgrade is observably no-op (RESEARCH "Migration note").

---

### `build.gradle.kts` (MODIFY — minimal, possibly none)

**Analog:** itself; existing `tasks.test { ... forkEvery=1 ... }` block at lines 60-78.

**Notes for planner:**
- D-08 explicitly forbids wrapper scripts. Phase 20 should NOT add a `runProfileServer` Gradle task — JVM flags ship as docs in `20-RUNTIME.md`.
- One acceptable change per RESEARCH §Plan 4: optional `tools/run-server-100bots.sh.example` etc. as `.example` documentation files (not committed scripts that are run by CI).
- D-06 confirms profile runs use the standalone `loadHarnessJar` (already at `build.gradle.kts:95-102`) — no new task.
- If async-profiler is committed in-tree (Open Question in RESEARCH), `.gitignore` may need an `out/` exclusion under `tools/async-profiler/` — but Plan 1's preferred path is the doc-only `tools/async-profiler-bootstrap.md`.

---

### `CLAUDE.md` (MODIFY — D-15 §Runtime tuning subsection)

**Analog:** itself — existing `§Outbound concurrency`, `§Connection model`, and `§markStalled close-then-best-effort-OOB` subsections are the structural template.

**Style template** (subsection structure copied from existing CLAUDE.md `§markStalled close-then-best-effort-OOB (Phase 19.1, D-07):`):

```markdown
### Runtime tuning (Phase 20)

Per-connection overhead reduction at scale lives in `paralife.runtime.jetty.*` and
`paralife.runtime.app.*` `@ConfigurationProperties` records (Phase 20 D-07). JVM
flags ship as documented per-scale-tier presets in
`.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md`, NOT
as wrapper scripts.

**The WS:entity 1:1 model from §Connection model is non-negotiable.** Tuning
reduces per-connection cost; it does not collapse connections. See `20-RUNTIME.md`
§1 for the full rationale and `20-RUNTIME.md` §3 for per-tier recipes.

The two metric gauges to watch when tuning are `paralife.tick.health.work-time-ms`
(`AdmissionMetrics.java:65`) and `paralife.outbound.detach.timeout`
(`AdmissionMetrics.java:74`).
```

**Notes for planner:**
- Insert AFTER the existing `§Connection model (Phase 18, D-05 / D-21)` block and before `§Project Skills` per RESEARCH §8.
- Concise, like `§markStalled close-then-best-effort-OOB` (~12 lines), not a full spec — `20-RUNTIME.md` is the authoritative document.

---

### `README.md` (MODIFY — D-16 operator paragraph)

**Analog:** none structurally — file is currently `# paralife` (1 line). Pattern borrowed from RESEARCH §8 template.

**Template** (RESEARCH §8 README insertion paragraph):

```markdown
## Runtime tuning

Paralife is built around many concurrent WebSocket connections — one per entity, by
design (see [`CLAUDE.md`](CLAUDE.md) §Connection model). At scale, per-connection
overhead is reduced via the four-layer tuning surface in
[`.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md`](.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md):
JVM flags, Jetty/network, application-level, and codec internals.
**Multi-entity-per-connection is not part of the design** — operational scale-out
is achieved by running more JVMs and more connections, not by collapsing them.
```

**Notes for planner:**
- README needs minimal scaffolding (project name, one-line value-prop, build/run links). RESEARCH §8 acknowledges this. Plan 6 may need to add ~15-25 lines of structure beyond the Runtime tuning paragraph — not just the one paragraph.
- Cross-link target `20-RUNTIME.md` must exist when the README is committed.

---

### `.planning/phases/20-.../20-RUNTIME.md` (NEW)

**Analog:** `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` (~25 KB) and `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md`

**Header pattern** (from `17-ADMISSION.md:1-9`):

```markdown
# Phase 17: Durable Admission Control Spec

**Phase:** 17-durable-admission-control-backpressure
**Status:** Authoritative — locks D-05, D-07, D-08
**Requirements:** SCALE-01 (durable admission), SCALE-02 (overload / backpressure)
**Supersedes:** backlog 999.1 temporary cap stopgap

---

## §1 Token Taxonomy (D-07)
```

**Section list (RESEARCH §7):**

```
§1 Architectural Principle: WS:entity 1:1 (D-01 / D-02)
§2 Tuning Surface (D-07)
§3 Per-Scale-Tier Recipes (§3.1 100-bot / §3.2 500-bot / §3.3 1000-bot)
§4 Profile Findings (§4.1 Methodology / §4.2 Headline numbers / §4.3 Per-tier narrative / §4.4 Codec opts)
§5 Forward Notes
§6 Profile Index
```

**Cross-reference style** (from `18-HARNESS.md:7`):

```markdown
See also: `17-ADMISSION.md` §1 (token taxonomy), §3 (FSM including STALLED), §4 (resume-token lifecycle); `15-SCHEMA.md` §6.1 (`r|` grammar — milestone-locked, not extended here).
```

**Notes for planner:**
- Target depth: ~25 KB (size of `17-ADMISSION.md`) — long enough to be authoritative, short enough to be readable.
- D-19: every committed baseline JFR cited in §6 Profile Index must include the `c22e487` SHA in its filename.
- D-13/D-18 headline numbers go in §4.2 — every recipe in §3 cites a specific JFR file from §6.

---

## Shared Patterns

### `@ConfigurationProperties` record + `@DefaultValue` + `@ConstructorBinding` + compact-ctor validation
**Source:** `src/main/java/com/paralife/admission/AdmissionConfig.java:22-46,113-148`
**Apply to:** `JettyRuntimeConfig.java`, `AppRuntimeConfig.java`
**Excerpt:** see Pattern Assignment for `JettyRuntimeConfig` above.

### Auto-discovery via `@ConfigurationPropertiesScan`
**Source:** `src/main/java/com/paralife/ParalifeApplication.java:8`
**Apply to:** All new records — no manual `@EnableConfigurationProperties` needed.

```java
@ConfigurationPropertiesScan
public class ParalifeApplication { ... }
```

### Per-decision javadoc citations
**Source:** `AdmissionConfig.java:12-17` (cites D-01, D-15, D-16); `OutboundSender.java:20-44` (cites D-10, D-11)
**Apply to:** Every Phase 20 source file. Cite D-01..D-20 inline in class/field javadoc.

```java
/**
 * <p>Decisions:
 * <ul>
 *   <li>D-07: four-layer tuning surface</li>
 *   <li>D-09: @ConfigurationProperties records mirror project pattern</li>
 *   <li>D-20: layer alongside paralife.admission.backpressure (do not move)</li>
 * </ul>
 */
```

### Inline-comment style citing CLAUDE.md and phase spec
**Source:** `OutboundSender.java:274-280` (Phase 19.1 D-18 frame-drop contract); `WorldWebSocketHandler.java:947-948`
**Apply to:** D-02 inline comments at `WorldWebSocketHandler.afterConnectionEstablished` and `OutboundSender.attachSession`.

```java
// Phase 19.1 D-18 — frame-drop contract at close.
// [why], [what was rejected], [where the spec lives].
// See class-level Javadoc for the full close-time contract.
```

### YAML defaults block with per-field `[live-tunable | launch-only]` comments
**Source:** `application.yml:36-47` (Phase 15 + 16 comments above each `paralife.websocket.*` field)
**Apply to:** new `paralife.runtime.*` block.

```yaml
paralife:
  websocket:
    # Phase 15 UAT Test 7 follow-up: RFC 6455 server→client PING cadence (ticks).
    # 30 ticks × 500ms = 15s — comfortably under the idle-timeout cap below.
    keepalive-ticks: 30
    # Jetty server-side read-idle close, defensive fallback if pings fail.
    idle-timeout-ms: 60000
```

### Spec-doc-per-phase `§N` numbered structure
**Source:** `17-ADMISSION.md` (§1 Token Taxonomy → §6+); `18-HARNESS.md` (§1 Architectural Principles → §N)
**Apply to:** `20-RUNTIME.md`.

### Phase 17 D-15 "tuning lives in config" precedent
**Source:** `AdmissionConfig.java:8-17` decision-list comment
**Apply to:** `JettyRuntimeConfig` and `AppRuntimeConfig` cite D-15 as their pattern ancestor.

---

## No Analog Found

| File | Role | Reason |
|---|---|---|
| `.planning/phases/20-.../profiles/*.jfr`, `*.html`, `*.meta.json` | binary/HTML evidence artifacts | First phase to commit JFRs and async-profiler flamegraphs in-tree. Filename convention (`<event>-<scenario>-<state>-<sha>.<ext>`) is established by D-19 in CONTEXT, not by any prior file. |
| `.planning/phases/20-.../profiles/README.md` | profile-dir README | No `.planning/.../profiles/` precedent in the repo. Content (filename convention, ritual, SHA-anchoring discipline) comes from CONTEXT D-05 / D-19 + RESEARCH §Pitfall 1 ritual block, not a copy-paste analog. |
| `tools/async-profiler-bootstrap.md` | bootstrap doc for an external tool | No `tools/` directory exists in repo (`ls /home/mark/kramtime/paralife/tools` returns nothing). Content sourced from official async-profiler README + RESEARCH §async-profiler simultaneous capture block. Planner should produce from scratch using the JFR/profiler bash blocks already in RESEARCH lines 361-428. |

For these three, the planner should consume RESEARCH.md directly (RESEARCH lines 361-450 carry verified bash/filesystem-discipline templates) rather than searching for an in-repo pattern.

---

## Metadata

**Analog search scope:**
- `src/main/java/com/paralife/{admission,codec,engine,websocket,world}/` — full read of `AdmissionConfig`, `AdmissionBeansConfig`, `SpawnConfig`, `TickConfig`, `JettyDeflateCustomizer`, `WebSocketConfig`, head of `OutboundSender`, head of `WorldWebSocketHandler` (lines 315-355), head of `PerceptionCodec` and `Base64Codec`, head of `TickBroadcaster`.
- `.planning/phases/17-.../17-ADMISSION.md` and `.planning/phases/18-.../18-HARNESS.md` — header + first-section format only.
- `src/main/resources/application.yml:1-90`.
- `build.gradle.kts:60-133`.
- `CLAUDE.md` (already loaded via system prompt).

**Files scanned:** ~14 source files, 2 spec docs, 1 yaml, 1 build script, top-level CLAUDE.md and README.md.

**Pattern extraction date:** 2026-05-09
**Anchor commit (per CONTEXT D-19):** `c22e487`
