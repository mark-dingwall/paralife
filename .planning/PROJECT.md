# Project

## What This Is

Paralife is a distributed living simulation: a toroidal 2D world populated by competing entity types whose local rules produce larger-scale behaviors. A Spring Boot server runs the tick loop, projects vision-scoped state over WebSocket, and accepts actions from autonomous bot clients. The project now spans bonding, composites, metabolism, environment, and compact-text transport, and is being used as a testbed for emergent behavior and future scale work.

## Core Value

Emergent spatial behaviour from simple local rules. Paralife is most valuable when small, understandable mechanics combine into patterns that are reproducible enough to study and surprising enough to be interesting.

## Current State

Project release status: unreleased.

Internal milestone `v2.0 Combination & Emergence` completed and was archived on `2026-04-22`.

Active milestone `v3.0 Scale Engineering (M4)` started on `2026-04-22`.

- Bonding, composite organisms, metabolism, and environmental systems are all live.
- The transport layer is compact-text, codec-native, Jetty-based, and exercised by stateless bot clients.
- Emergence instrumentation, deterministic seeding, long-run fixtures, and the `16-EMERGENCE.md` narrative are in place.
- The current temporary world-level registration cap closes a real admission-control gap; this milestone replaces that stopgap with durable admission control and scale-safe backpressure.

## Current Milestone: v3.0 Scale Engineering (M4)

**Goal:** Prove the architecture handles large-scale externally driven load without losing simulation correctness or operational control.

**Target features:**
- Durable world-level admission control and overload/backpressure policy that replaces the temporary population cap.
- External multi-process load harness with harness identity, per-harness metrics, and repeatable 100/500/1000+ bot runs.
- Scale-path infrastructure for partition-aware execution, high-density placement, and reduced socket/process overhead.
- Benchmark and tuning evidence for tick drift, session stability, and throughput under sustained load.

## Requirements

### Validated

- **v1.0 — Foundation & Living Simulation** completed the baseline world, tick loop, WebSocket bot control, and 100-bot load envelope as an internal milestone.
- **v2.0 — Combination & Emergence** completed bonding, composites, metabolism, environmental rules, protocol overhaul, live operator tooling, deterministic emergence tests, and the final regression gate as an internal milestone.

### Active

- **v3.0 / M4 — Scale Engineering** is active.
- Durable admission control must replace the temporary `max-active-entities` gate with a policy the server can explain, meter, and evolve.
- External load generation must scale beyond `BotRunner`'s single-process 100-bot envelope.
- The scale milestone must produce benchmark evidence, not just code paths.

### Out of Scope

- **M5: Observability & Operations** — richer metrics, tracing, graceful shutdown polish, live world visualizer.
- **M6: Deployment** — Dockerization, CI/CD, hosting, multi-region, chaos engineering.
- **Offspring bot-agency / flower visualizer fallback (`999.2`)** — deferred to future M5-facing work; it is not needed to prove scale behavior.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.4.4 |
| Transport | Raw WebSocketHandler on Jetty 12 |
| Protocol | Compact text codec shared by server and bot client |
| Build | Gradle Kotlin DSL |
| Testing | JUnit 5, full suite green including long-run gate |

## Milestone Focus

- Replace the temporary WebSocket caps with durable registration / admission policy.
- Stand up a real external load harness and keep `BotRunner` as the small-N operator path.
- Decide how far partitioning and connection multiplexing need to go in this milestone to support the benchmark gate.
- Close M4 with repeatable 100/500/1000+ load evidence and documented tuning guidance.

## Historical Context

<details>
<summary>Archived internal milestone artifacts</summary>

- `.planning/milestones/v1.0-ROADMAP.md`
- `.planning/milestones/v1.0-MILESTONE-AUDIT.md`
- `.planning/milestones/v2.0-ROADMAP.md`
- `.planning/milestones/v2.0-REQUIREMENTS.md`
- `.planning/milestones/v2.0-MILESTONE-AUDIT.md`

</details>
