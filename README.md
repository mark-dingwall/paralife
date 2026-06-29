# paralife

A toroidal 2D living simulation. A Spring Boot server runs the physics tick
loop, broadcasts state via WebSocket, and receives actions from autonomous
heuristic bot clients. Built with Java 21 virtual threads for massive
concurrency with simple blocking code.

Three competing entity types (Catalyst, Membrane, Spore) follow rock-paper-scissors
dynamics in a closed energy economy — emergent spatial behaviour (spiral waves,
population oscillations, niche formation) arises from simple local rules.

## Build & run

```bash
./gradlew test              # JUnit 5 unit + integration suite
./gradlew bootRun           # Start server on :8080
./gradlew loadHarnessJar    # Build the standalone load harness
./gradlew runBot            # Operator CLI — launches up to 100 bots per invocation
```

## Project layout

- `src/main/java/com/paralife/` — server (`world`, `engine`, `websocket`, `admission`, `codec`, `runtime`, `harness`, `metrics`, `diagnostics`, `bot`)
- `src/test/java/com/paralife/` — JUnit 5 unit + integration tests
- [`docs/`](docs/README.md) — live reference: capability contracts (wire protocol, admission, harness, runtime) + architecture internals
- `.planning/`, `.gsd/` — frozen GSD-era planning history (see [`.planning/README.md`](.planning/README.md))
- [`CLAUDE.md`](CLAUDE.md) — project constitution: conventions, architecture, the working loop
- [`BACKLOG.md`](BACKLOG.md) — live backlog: deferred work with triggers + anchors

## Runtime tuning

Paralife is built around many concurrent WebSocket connections — one per entity,
by design (see [`docs/HARNESS.md`](docs/HARNESS.md) §1). At scale,
per-connection overhead is reduced via the four-layer tuning surface in
[`docs/RUNTIME.md`](docs/RUNTIME.md):
JVM flags, Jetty/network knobs (`paralife.runtime.jetty.*`), application-level
knobs (`paralife.runtime.app.*`), and codec internals.

**Multi-entity-per-connection is not part of the design** — operational scale-out
is achieved by running more JVMs and more connections, not by collapsing them
(WS:entity 1:1 is the architectural identity; SCALE-08's "or equivalent
transport-level scale strategy" escape hatch — Phase 20 D-01).
