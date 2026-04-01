# Paralife — Project Roadmap

> A massively parallel digital primordial soup. Spring Boot + Virtual Threads + WebSocket.
> Portfolio piece demonstrating scale engineering for Canva backend engineer application.

---

## M1: Foundation — Tick Engine & Empty World
**Goal:** Spring Boot server boots, manages a 2D toroidal grid, runs a tick loop, accepts WebSocket connections.

- [ ] S01: Spring Boot project scaffold (Java 21, Gradle, Spring Web + WebSocket)
- [ ] S02: World grid model (toroidal 2D grid with configurable dimensions)
- [ ] S03: Tick engine (fixed-rate tick loop, tick counter, broadcast tick events)
- [ ] S04: WebSocket connection manager (connect, auth, assign to cell, disconnect)
- [ ] S05: Integration test — 100 bots connect, receive tick events, verify ordering

**After this:** Server runs, bots connect, ticks flow. Nothing *happens* yet.

---

## M2: Entities & Actions — The Primordial Soup
**Goal:** Entities (cells, proteins, food, toxins) exist on the grid. Each tick, every entity performs one action.

- [ ] S01: Entity type system (Cell, Protein, FoodSource, Toxin — each with properties)
- [ ] S02: Action system (Move, Consume, Emit, Bond, Split, Idle)
- [ ] S03: Bot behavior strategies (random, chemotaxis, predator, passive)
- [ ] S04: Tick action resolution (collect → validate → resolve conflicts → apply)
- [ ] S05: Spatial queries (neighbors, line-of-sight, chemical gradients)

**After this:** The soup is alive — things move, eat, and die.

---

## M3: Combination & Emergence
**Goal:** Simple entities combine into complex organisms when conditions align.

- [ ] S01: Bonding rules engine (which entity types can combine, under what conditions)
- [ ] S02: Composite entities (multi-cell organisms with shared state)
- [ ] S03: Energy/metabolism system (entities need food, starve, reproduce)
- [ ] S04: Environmental rules (toxin spread, food regeneration, decay)
- [ ] S05: Emergent behavior tests (given seed X, verify known patterns emerge)

**After this:** Complex life emerges from simple rules.

---

## M4: Scale Engineering — 10K+ Concurrent Entities
**Goal:** Prove the architecture handles massive parallelism.

- [ ] S01: World partitioning (spatial sharding, partition-aware tick engine)
- [ ] S02: Virtual thread tuning (thread pool sizing, pinning avoidance, profiling)
- [ ] S03: Backpressure & flow control (slow clients, tick skipping, catch-up)
- [ ] S04: Connection multiplexing (one bot pod ↔ many entities over fewer sockets)
- [ ] S05: Load test harness (configurable bot count, metrics collection, reports)

**After this:** Benchmark data showing throughput, latency percentiles, connection limits.

---

## M5: Observability & Operations
**Goal:** Production-grade observability — the kind Canva would expect.

- [ ] S01: Structured logging (tick ID, entity ID, action correlation)
- [ ] S02: Metrics (Micrometer → Prometheus: tick duration, entity count, connection count, action throughput)
- [ ] S03: Distributed tracing (trace a single tick across partitions)
- [ ] S04: Health checks & graceful shutdown (drain connections, complete current tick)
- [ ] S05: Live world visualizer (simple web UI showing grid state in real-time)

**After this:** Dashboards, traces, and a visual demo you can show in an interview.

---

## M6: Deployment — Fly.io + Railway + Proxies
**Goal:** Deploy at scale with realistic network conditions.

- [ ] S01: Dockerize world server, deploy to fly.io (multi-region)
- [ ] S02: Dockerize bot pods, deploy to Railway (auto-scaling)
- [ ] S03: Proxy layer (NGINX/Envoy, random routing, artificial latency/jitter)
- [ ] S04: CI/CD pipeline (GitHub Actions → build → test → deploy)
- [ ] S05: Chaos engineering (kill nodes, partition network, verify recovery)

**After this:** Live system running at scale, accessible via URL, with chaos resilience proof.

---

## Milestone Dependencies
```
M1 → M2 → M3
M1 → M4 (can start after M1, parallel with M2/M3)
M2 → M5 (needs entities for meaningful metrics)
M4 + M5 → M6 (deploy after scale + observability are proven)
```

## Tech Stack
| Component | Technology |
|-----------|-----------|
| Language | Java 21+ (virtual threads) |
| Framework | Spring Boot 3.x |
| Connectivity | Spring WebSocket |
| Build | Gradle (Kotlin DSL) |
| Testing | JUnit 5 + Testcontainers |
| Metrics | Micrometer → Prometheus |
| Tracing | OpenTelemetry |
| Logging | SLF4J + Logback (structured JSON) |
| World Server | fly.io |
| Bot Pods | Railway |
| Proxies | NGINX or Envoy |
| CI/CD | GitHub Actions |
