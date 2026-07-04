# External Load Harness

**Status:** Live capability contract.

The standalone load harness (> 100 bots), harness-identity attribution in metrics/logs, and the
WS:entity 1:1 connection model with its design ceilings. Any change updates this doc before code lands.

See also: `ADMISSION.md` §1 (token taxonomy), §3 (FSM including STALLED), §4 (resume-token lifecycle); `SCHEMA.md` §6.1 (`r|` grammar — milestone-locked, not extended here).

---

## §1 Architectural Principles

### WS:Entity 1:1 (D-05 / D-21)

**The fundamental architectural principle: one WebSocket connection per entity.**

Many concurrent WebSocket connections is a stated architectural goal — Paralife deliberately pursues
a massively parallel architecture that demonstrates scale via concurrent connections, not via
multiplexing. All scale-out is done by running more connections (more bots, more harness JVMs),
never by sharing a single connection across multiple entities.

**WS:entity 1:1** — every entity on the grid has exactly one WebSocket session, and every WebSocket
session owns exactly one entity during the Alive phase. Enforced by the session FSM in
`WorldWebSocketHandler` (see `ADMISSION.md §3`). Exception: STALLED-held entities during the
grace window (Phase 17 D-13) sit on the grid with no active session pending rebind.

**Multi-entity-per-session is strongly discouraged but not banned.** Exceptions are reviewed
case-by-case; deviation requires an explicit justification in an ADR or future-phase spec. The WS
FSM, admission gate, resume-token registry, and per-session outbound queue are all designed around
the 1:1 invariant. Violating it requires redesigning all four.

### Scale Model (D-01 / D-02)

- **Single JVM per harness process.** `LoadHarness` is a standalone fat-JAR that manages one pool
  of bots. Operational scale-out is N independent harness JVMs side-by-side (e.g., `10×100`,
  `4×250`). Per-harness identity (D-06 / D-07) makes server-side attribution work naturally across
  instances without any built-in cross-instance orchestrator.
- **Design ceiling: 5 000 concurrent WS connections per JVM.** 5× headroom over Phase 21's
  1 000+ benchmark target. Java 21 virtual threads provide the concurrency fabric — one VT per bot,
  blocking I/O, simple code.
- **`BotRunner` stays the ≤100 operator path (SCALE-05).** The harness does not replace
  `BotRunner`; it extends the scale ceiling. Phase 21 benchmark evidence will determine actual
  production envelope values.

### Connection Model (D-05 / D-21)

| Dimension | Value |
|-----------|-------|
| Connections per entity | **1** (invariant) |
| Entities per connection | **1** (Alive phase) |
| Scale-out strategy | N independent harness JVMs |
| Multiplexing | Strongly discouraged; case-by-case ADR if needed |
| Exception trigger | Explicit justification in ADR or future-phase spec |

Cross-reference: `docs/ARCHITECTURE.md §Connection model`; `ADMISSION.md §3` (FSM diagram).

---

## §2 Identity Wire Shape

### Headers (D-06)

Harness identity rides on the WebSocket **handshake via HTTP headers**:

| Header | Value | When Present |
|--------|-------|-------------|
| `X-Paralife-Source` | `operator` \| `harness` \| `unknown` (client-allowed subset of the source taxonomy — see §4) | Always |
| `X-Paralife-Harness` | `<harness-id>` | Only when `X-Paralife-Source: harness` |

The header path was chosen over:
- A new `r|` grammar slot — `SCHEMA.md` §6.1 is milestone-locked; no codec change.
- URL query params — harness ids leak into proxy logs.
- A control frame — adds a round-trip without buying flexibility.

Server reads headers in `WorldWebSocketHandler.afterConnectionEstablished` via
`session.getHandshakeHeaders()` (Spring `WebSocketSession` API) and stashes as session attributes
(`AttributionTagger.ATTR_SOURCE`, `AttributionTagger.ATTR_HARNESS`). These attributes are
preserved across the STALLED-pivot rebind path (T-18-04 mitigation — see §9).

**Canonical harness-id policy**:

```
^[A-Za-z0-9-]{1,32}$
```

Single source of truth. Implemented by `com.paralife.admission.AttributionSanitizer#sanitizeHarnessId(String)` —
both `com.paralife.bot.BotIdentity` (client-side compact ctor) and the server-side handshake
header path delegate to it (P18-Chunk-A remediation).

Non-conformant input (over-length, spaces, `=`, `/`, `_`, non-ASCII, ASCII control chars
including CR/LF) is **rejected**, not silently truncated — the sanitizer's job is to gate
header values, not coerce them.

**`source=harness` invariant** (server-side, P18-Chunk-A remediation): when a client sends
`X-Paralife-Source: harness` but the harness header is missing or sanitizer-rejected, the
server folds source to `unknown`. This preserves the bidirectional invariant
`source=harness ⇔ harness id present` matching `BotIdentity`'s compact-ctor enforcement.

### Identity Granularity (D-07)

**Per-process.** One harness id per JVM; all bots in that process share it. This gives bounded
Micrometer cardinality. Per-bot triage continues to use existing `entityId` / `sessionId` in logs.

### Default When Absent (D-08)

`X-Paralife-Source: unknown` if the header is absent. Reserved for sessions whose origin cannot be
classified (ad-hoc `wscat` probes, integration tests that don't set headers).

### BotRunner Identity (D-09)

`BotRunner` explicitly sets `X-Paralife-Source: operator` (no `X-Paralife-Harness`). This keeps
`unknown` semantically distinct from the supported ≤100 operator path.

### Cardinality Policy (D-10)

Cap of **64 distinct `harness` tag values** per JVM lifetime. The 65th and beyond fold to
`harness=overflow`. A one-time WARN is emitted on first overflow:

```
HARNESS overflow first-seen tick=<n> harness-id=<truncated>
```

Config: `paralife.admission.attribution.max-harness-cardinality` (default 64).
A `MeterFilter` registered in `AdmissionMetrics` acts as defense-in-depth after
`AttributionTagger`'s primary folding.

---

## §3 CLI Surface

### Packaging (D-15)

Two build outputs:

| Artifact | Gradle task | Main class |
|----------|------------|-----------|
| Server fat-JAR | `bootJar` (existing) | `com.paralife.ParalifeApplication` |
| Harness fat-JAR | `loadHarnessJar` | `com.paralife.harness.LoadHarness` |

Dev iteration: `./gradlew runHarness --args='...'` (JavaExec, same main class).

### Config Surface (D-16)

CLI flags only; `PARALIFE_HARNESS_*` env vars as overrides for headless contexts.
No YAML config file this phase.

`--duration` is an **INTEGER seconds** value (not a duration string). Default: indefinite
(SIGTERM / SIGINT exit clean). Example: `--duration 300` for a 5-minute run.

| Flag | Env Override | Default | Description |
|------|-------------|---------|-------------|
| `--server-uri` | `PARALIFE_HARNESS_SERVER_URI` | (required) | WebSocket endpoint |
| `--count` | `PARALIFE_HARNESS_COUNT` | (required) | Number of bots |
| `--harness-id` | `PARALIFE_HARNESS_ID` | auto-generated | Process-level identity tag |
| `--ramp-up` | `PARALIFE_HARNESS_RAMP_UP` | `rate:50` | `instant \| rate:<n> \| wave:<count>:<sleepMs>` |
| `--species-mix` | `PARALIFE_HARNESS_SPECIES_MIX` | `balanced` | `balanced \| <C-frac>:<M-frac>:<S-frac>` |
| `--duration` | `PARALIFE_HARNESS_DURATION` | `0` (indefinite) | INTEGER seconds; 0 = indefinite |
| `--report-out` | `PARALIFE_HARNESS_REPORT_OUT` | `./harness-<id>-report.json` | Report file path |
| `--report-mode` | `PARALIFE_HARNESS_REPORT_MODE` | `overwrite` | `overwrite \| append` |
| `--report-interval` | `PARALIFE_HARNESS_REPORT_INTERVAL` | `30` | Seconds between writes (10–300) |

### Ramp-Up Modes (D-03)

| Mode | Syntax | Behavior |
|------|--------|----------|
| `instant` | `--ramp-up=instant` | All VTs fire in a tight loop. Maximises stress on `tick-overload` gate. |
| `rate:N` | `--ramp-up=rate:50` | N bot-starts per second; keeps tick-overload gate calm during ramp. |
| `wave:C:S` | `--ramp-up=wave:10:500` | Bursts of C bots, then S ms pause; for synthetic traffic shapes. |

---

## §4 Attribution Tagging Schema

### Source Taxonomy (D-11)

`source` ∈ `{operator, harness, unknown, overflow, offspring}` — bounded, immutable set.

| Value | Meaning | Client-allowed |
|-------|---------|----------------|
| `operator` | `BotRunner` ≤100 operator path | yes |
| `harness` | `LoadHarness` external harness | yes |
| `unknown` | Absent or unrecognized header | yes |
| `overflow` | Server-side cardinality fold result (D-10) — applied to tag, not to `source` | **no** (P18-Chunk-A: reserved server-only) |
| `offspring` | **Reserved** — no producer this phase; see §10 Forward Notes | **no** (P18-Chunk-A: reserved server-only) |

**Client-allowed subset** (`com.paralife.bot.BotIdentity#CLIENT_ALLOWED_SOURCES`): only
`{operator, harness, unknown}` are accepted from `X-Paralife-Source` headers. A client
attempting to spoof `overflow` or `offspring` is folded to `unknown` —prevents pollution
of the cardinality-fold bucket and pre-emption of the future D-20 producer.

**Note:** `offspring` is reserved from day one so dashboards and grafana queries can treat it as a
known future value and avoid rework when backlog 999.2 ships.

### Tag Schema (D-11 / D-12)

When `source=harness`, the `harness=<id>` tag is also emitted (bounded by D-10 cardinality cap).
For all other sources, only the `source` tag is emitted.

### Metrics Gaining Tags (D-12)

| Metric | Type | Tags | Notes |
|--------|------|------|-------|
| `paralife.admission.rejected` | Counter | `reason, source[, harness]` | Extends Phase 17 D-17 |
| `paralife.admission.active.entities` | Gauge | `source[, harness]` | Extends Phase 17 D-18 |
| `paralife.backpressure.stalled.sessions` | Gauge | `source[, harness]` | Extends Phase 17 D-18 |
| `paralife.admission.ingress.overwrites` | Counter | `source[, harness]` | Extends Phase 17 D-09 |

### Metrics Staying Scalar (D-12)

| Metric | Reason |
|--------|--------|
| `paralife.admission.maintenance` | Server-global flag, not per-source |
| `paralife.tick.health.work-time-ms` | Tick health is a server property, not per-session origin |

---

## §5 Log Marker Catalog

Phase 18 extends the Phase 17 log marker channels with two new sources and a new lifecycle channel.

### Existing Channels Extended (D-13)

All existing `ADMISSION` and `BACKPRESSURE` markers gain `source=<v>[ harness=<id>]` fields.
TICK-HEALTH stays scalar (server-global property — no per-session origin to attribute):

```
ADMISSION rejected tick=<n> session=<sid> reason=<token> source=<v> [harness=<id>]
BACKPRESSURE stalled tick=<n> session=<sid> source=<v> [harness=<id>]
BACKPRESSURE resumed tick=<n> session=<sid> entity=<eid> source=<v> [harness=<id>]
BACKPRESSURE held-on-close tick=<n> session=<sid> status=<s> entity=<eid> source=<v> [harness=<id>]
TICK-HEALTH degraded tick=<n> work-ms=<ms> high-water-pct=<pct>
TICK-HEALTH recovered tick=<n> work-ms=<ms> low-water-pct=<pct>
```

`harness=<id>` field is present only when `source=harness`. `BACKPRESSURE held-on-close` is
emitted by `WorldWebSocketHandler` when a transport-close occurs while the entity is held under
the STALLED grace window — operational signal that an entity is awaiting rebind, distinct from
the lifecycle `HARNESS disconnected reason=stalled-held` marker below.

### New Channel: HARNESS (D-14)

Session lifecycle markers for harness-identified connections:

```
HARNESS connected    tick=<n> session=<sid> harness=<id|-> source=<v> active=<count>
HARNESS disconnected tick=<n> session=<sid> harness=<id|-> source=<v> reason=<reason>
```

Where `<reason>` ∈ `{graceful, stalled-held}`:

- `graceful` — clean disconnect, no STALLED state, no rejection.
- `stalled-held` — session was in STALLED state when the underlying transport closed; entity is
  held under grace window for potential rebind (Phase 17 D-13 STALLED-pivot). NEW in Phase 18 —
  emitted by `WorldWebSocketHandler.afterConnectionClosed` on the `wasStalled` branch (Plan 02).

`reason=stalled-held` (STALLED-pivot graceful close) is greppable — see Operator Cheat Sheet below.

### HARNESS Overflow Marker (D-10)

```
HARNESS overflow first-seen tick=<n> harness-id=<truncated>
```

One-time WARN per JVM lifetime on first cardinality-cap overflow.

### Operator Cheat Sheet

```bash
grep -E 'ADMISSION|BACKPRESSURE|TICK-HEALTH' server.log    # all admission/health events
grep 'harness=harness-A' server.log                         # per-instance triage
grep 'reason=stalled-held' server.log                       # stall-pivot events
grep 'HARNESS overflow' server.log                          # cardinality cap breaches
```

---

## §6 JSON Report Schema (D-17)

### Wire Format

All JSON is snake_case (Jackson `PropertyNamingStrategies.SNAKE_CASE` at `ObjectMapper` level;
Java field names stay camelCase). File is written via atomic temp-rename — external readers never
observe a half-written file.

### Header Object

Written once at startup (append mode) or on every write (overwrite mode, merged):

```json
{
  "harness_id": "harness-abc123",
  "server_uri": "ws://localhost:8080/ws/world",
  "target_count": 1000,
  "start_wall_time": "2026-04-28T12:00:00Z",
  "jvm_version": "21.0.6",
  "build_sha": null
}
```

### Counter Object

Written periodically (`report_interval_seconds`; default 30):

```json
{
  "peak_registered": 997,
  "current_registered": 994,
  "connect_failures_total": 3,
  "e408_reconnect_required_total": 12,
  "respawns_total": 450,
  "actions_sent_total": 298000,
  "perceptions_received_total": 298000,
  "syncs_received_total": 1009,
  "wall_time_seconds_elapsed": 120,
  "exit_reason": null,
  "server_metrics": {
    "paralife.tick.drift.millis": 4.2,
    "paralife.ws.active.sessions": 994.0,
    "paralife.backpressure.stalled.sessions": 0.0,
    "paralife.backpressure.stalled.total": 0.0,
    "paralife.backpressure.rebound": 0.0,
    "paralife.backpressure.terminal.dropouts": 0.0,
    "paralife.admission.rejected": 3.0
  }
}
```

`server_metrics` (Task 3, Phase 21) folds a fixed set of server-side `/actuator/metrics` readings
into every counter write — always the full `ReportSnapshot.BENCHMARK_METER_NAMES` key set, with
`null` for any meter the scrape couldn't reach (fail-soft; a benchmark run never fails on a missing
meter). Meter → statistic:

| Meter | Statistic | SC category |
|-------|-----------|--------------|
| `paralife.tick.drift.millis` | `MAX` | Tick drift |
| `paralife.ws.active.sessions` | `VALUE` | Session stability |
| `paralife.backpressure.stalled.sessions` | `VALUE` | Session stability |
| `paralife.backpressure.stalled.total` | `COUNT` | Session stability |
| `paralife.backpressure.rebound` | `COUNT` | Session stability |
| `paralife.backpressure.terminal.dropouts` | `COUNT` | Session stability |
| `paralife.admission.rejected` | `COUNT` (aggregate; by-reason breakdown deferred to BACKLOG) | Rejection counts |

`exit_reason` is present **only on the final write**. Values:

| Value | Trigger |
|-------|---------|
| `signal` | SIGINT or SIGTERM (indistinguishable at JVM level) |
| `duration-reached` | `--duration` elapsed |
| `fatal-error` | Unrecoverable exception in main loop |

Note: SIGINT and SIGTERM map to the same `signal` value — JVM shutdown hooks cannot reliably
distinguish them. Operators needing to distinguish should use process supervisor tooling.

### Write Modes

| Mode | Behavior |
|------|----------|
| `overwrite` (default) | Single JSON object always reflecting current state. `ReportSnapshot.merge(header, counters)` on every write — header fields never lost. |
| `append` | JSONL — first line is the header object; subsequent lines carry rolling counter objects only. |

---

## §7 Sample Benchmark Commands *(non-normative)*

All examples use integer seconds for `--duration` per D-16.

### 500-bot sustained run

```bash
java -jar build/libs/paralife-*-load-harness.jar \
  --server-uri ws://localhost:8080/ws/world \
  --count 500 \
  --harness-id harness-500 \
  --ramp-up rate:50 \
  --species-mix balanced \
  --duration 600 \
  --report-out ./harness-500-report.json \
  --report-mode append \
  --report-interval 30
```

Multi-instance pattern: run N independent harness JVMs with distinct `--harness-id`/`--report-out`
values, then aggregate the JSONL reports post-run via `jq -s` — see §10 Multi-Instance Harness
Coordination.

---

## §8 Threat Model

### Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Bot client → server | WebSocket handshake headers cross this boundary. `X-Paralife-Harness` and `X-Paralife-Source` are operator-provided; see T-18-01. |
| Harness → metrics pipeline | Micrometer tag values must be cardinality-bounded; see D-10. |

### STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation |
|-----------|----------|-----------|-------------|-----------|
| T-18-01 | Tampering — header injection | `X-Paralife-Harness` / `X-Paralife-Source` headers | Accept (operator-trusted input) | Sanitized by `AttributionSanitizer.sanitizeHarnessId`: regex `^[A-Za-z0-9-]{1,32}$`. Malformed ids are **rejected** (`Optional.empty`) — not truncated; the WS handler then folds `source` to `unknown` (`source=harness ⇔ harness id present` invariant). Source values not in `SOURCE_TAXONOMY` → `unknown`. |
| T-18-02 | DoS — Micrometer cardinality explosion | `harness` tag in `paralife.admission.*` | Mitigate | Primary: `AttributionTagger.foldHarnessIfOverCap` caps at 64 distinct ids + folds to `overflow`. Defense-in-depth: `MeterFilter.maximumAllowableTags` in `AdmissionMetrics`. One-time WARN on first fold. |
| T-18-03 | Information disclosure — harness id in proxy logs | `X-Paralife-Harness` as HTTP header | Accept | Harness ids are non-secret operator labels, not authentication credentials. URL query params were explicitly rejected for this reason (D-06). |
| T-18-04 | Information disclosure — silent attribution loss after STALLED-pivot rebind | `ResumeTokenRegistry.tryRebind` + new-session ATTR stash | Mitigate | `BotClient.connect()` re-sends `X-Paralife-Source` / `X-Paralife-Harness` on every `connect()` call (identity is a `final` field). Locked by `AttributionRebindTest` which exercises the full STALLED → E\|408 → reconnect → rebind cycle and asserts gauge and session attribute continuity. |

---

## §9 Security Domain

- **Header Injection (T-18-01):** see §8 STRIDE register for the threat and mitigation.
- **Canonical Harness-Id Policy:** see §2 Identity Wire Shape for the regex and single-source-of-truth statement.

---

## §10 Forward Notes

### `source=offspring` Reserved (D-20)

`offspring` is in `SOURCE_TAXONOMY` from day one. No producer ships this phase. Backlog 999.2
(bot-driven offspring) will populate this value when the server sends an offspring-offer opcode
(wire shape is 999.2's call, not this phase's). Dashboards built off this spec should treat
`offspring` as a known-future reserved value — no rework when 999.2 lands.

### Multi-Entity-per-Session (D-21)

Default policy is WS:entity 1:1 (§1). If an exception is ever reviewed:
- Requires explicit ADR with justification.
- Four subsystems must be redesigned: session FSM, admission gate, resume-token registry, per-session outbound queue.
- Fresh-WS-per-offspring (default 999.2 path) avoids this entirely — a new bot mints a fresh WS
  connection and uses a future claim opcode. This is cheaper than multiplexing given Phase 17's VT
  outbound sender and admission already handle N concurrent connections efficiently.

### Multi-Instance Harness Coordination

Cross-harness ramp synchronisation and unified run reporting are not built in. Operators:

- Run N independent harness JVMs with distinct harness ids.
- Use a shell sleep barrier or `wait` for synchronised ramp start.
- Aggregate results post-run via `jq` over per-harness JSONL files.

Phase 21 may introduce shell-orchestrated ramp scripts if the benchmark gate requires coordinated
multi-instance sweeps; the per-harness JSONL format is designed with this in mind.

### `BotFactory` Seam (D-19)

`BotFactory.create(char species, BotIdentity identity, Optional<String> claimEntityId, Optional<String> claimToken)`
is the single choke-point for bot construction. `claimEntityId` / `claimToken` params are reserved
no-ops today (always `Optional.empty()` from current call sites). When 999.2 ships,
a new bot-driven offspring event will trigger `BotFactory.create` with a non-null claim token,
minting a fresh WS connection to the same entity. No fleet-abstraction rework required.

---

## §11 Server-Side Metrics Scraping (Phase 21)

`com.paralife.harness.ServerMetricsScraper` is a read-only client for
`GET /actuator/metrics/{name}`, so server-side meters (tick-drift, rejections, session counts)
can be folded into the harness benchmark report. It is pure/side-effect-free beyond the HTTP GET:
an injected `HttpClient`, a per-meter statistic map (the meter set is heterogeneous — Counter→
`COUNT`, Gauge→`VALUE`, DistributionSummary→`MAX` — so the statistic is chosen per meter, not once
for the whole list), a per-request timeout under an overall ~2s whole-scrape budget
(`ServerMetricsScraper.TOTAL_BUDGET`, so the shutdown-hook final write can't stall for
`meters × timeout`), and fail-soft omission (a missing, erroring, or timed-out meter is left out of
the result, never thrown) — a benchmark run never fails on a missing meter.

**Endpoint dependency:** `ServerMetricsScraper.actuatorBaseFrom(serverUri)` derives the actuator
base from `--server-uri` (`ws→http`, `wss→https`, `/ws/world`→`/actuator/`). Root-deployment only —
no context-path handling.

**Aggregation caveat:** two-tag counters (e.g. `paralife.admission.rejected`, tagged by
`reason`+`source`) and multi-bucket gauges (e.g. `paralife.ws.active.sessions`,
`paralife.backpressure.stalled.sessions`, tagged by `source`+`harness`) are read via the base
endpoint, which returns the aggregate sum across all tags/buckets — a whole-server figure, not a
per-reason or per-source breakdown. Per-tag breakdown (`?tag=k:v` per `availableTags` value) is
deferred to `BACKLOG.md` §Phase-21 follow-ups.

Which meters are scraped and how the result folds into the report is wired by `LoadHarness`
into `ReportSnapshot.serverMetrics()` (Task 3, §6 above).

## §12 Repeatable Tier Sweep (Task 4)

`tools/benchmark/run-tiers.sh <ws-uri> [duration-seconds]` loops the three benchmark tiers
(100 / 500 / 1000), invoking `build/libs/*-load-harness.jar` per tier with pinned
`--ramp-up rate:50`, and writes each tier's report to a **fresh per-sweep directory**
`reports/run-<epoch-seconds>/bench-<tier>.json` — a new directory per invocation, so a stale
report from a prior sweep can never satisfy a verify glob. Each tier is gated in-script: a harness
non-zero exit **or** a degenerate report (`peak_registered == 0`, or no non-null server metric —
i.e. a dead / wrong server) is counted as a failure; the loop continues but the sweep exits
non-zero if any tier failed, rather than masking a bad run as green.

"Repeatable" means a re-runnable command with a saved report per tier — **not** bit-identical
numbers across runs (live-WS timing is unseeded). Keep `duration-seconds` inside Micrometer's
distribution-statistic-expiry window (~2 min) or the tick-drift MAX becomes recency-weighted.

```bash
./gradlew loadHarnessJar
./gradlew bootRun &
bash tools/benchmark/run-tiers.sh ws://localhost:8080/ws/world 120
```

The in-script per-report gate (also runnable by hand over *this* sweep's dir, never a prior one):

```bash
RUN=$(ls -td reports/run-*/ | head -1)
for f in "$RUN"bench-*.json; do
  jq -e '(.peak_registered // 0) > 0
         and ((.server_metrics // {}) | to_entries | any(.value != null))' "$f"
done
```

`paralife.tick.drift.millis` (MAX) and `paralife.ws.active.sessions` (VALUE) populate in any run.
`paralife.admission.rejected` and the `paralife.backpressure.*` stall meters are lazily
registered and event-gated — they read absent/`0` until the server actually rejects or stalls,
so a benign run legitimately leaves those categories empty; that is not a scraper defect.

A `@Tag("slow")` positive control for the scrape path itself — `ScrapeLiveIntegrationTest`
(`src/test/java/com/paralife/harness/`) — boots a `@SpringBootTest(webEnvironment = RANDOM_PORT)`
server on a random port and asserts `ServerMetricsScraper` returns a non-null
`paralife.ws.active.sessions` reading, with zero bots connected. Excluded from the default
`./gradlew test`; runs under `-PincludeLong=true`.

---

*Canonical harness spec for downstream Phase 21 benchmark scripts.*
*Cross-references: `ADMISSION.md` §1 (token taxonomy), `SCHEMA.md` §6.1 (`r|` grammar).*
