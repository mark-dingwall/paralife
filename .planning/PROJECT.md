# Project

## What This Is

Paralife is a distributed living simulation: a toroidal 2D world populated by competing entity types whose local rules produce larger-scale behaviors. A Spring Boot server runs the tick loop, projects vision-scoped state over WebSocket, and accepts actions from autonomous bot clients. The project now spans bonding, composites, metabolism, environment, and compact-text transport, and is being used as a testbed for emergent behavior and future scale work.

## Core Value

Emergent spatial behaviour from simple local rules. Paralife is most valuable when small, understandable mechanics combine into patterns that are reproducible enough to study and surprising enough to be interesting.

## Current State

Project release status: unreleased.

Internal milestone `v2.0 Combination & Emergence` completed and was archived on `2026-04-22`.

- Bonding, composite organisms, metabolism, and environmental systems are all live.
- The transport layer is compact-text, codec-native, Jetty-based, and exercised by stateless bot clients.
- Emergence instrumentation, deterministic seeding, long-run fixtures, and the `16-EMERGENCE.md` narrative are in place.
- The current temporary world-level registration cap closes a real admission-control gap; durable replacement work is backlog item `999.1`.

## Requirements

### Validated

- **v1.0 — Foundation & Living Simulation** completed the baseline world, tick loop, WebSocket bot control, and 100-bot load envelope as an internal milestone.
- **v2.0 — Combination & Emergence** completed bonding, composites, metabolism, environmental rules, protocol overhaul, live operator tooling, deterministic emergence tests, and the final regression gate as an internal milestone.

### Active

- No active milestone is defined yet. Start the next one with `$gsd-new-milestone`.

### Out of Scope

- **M4: Scale Engineering (10K+ entities)** — partitioning, real admission control, virtual-thread tuning, external load harnesses.
- **M5: Observability & Operations** — richer metrics, tracing, graceful shutdown polish, live world visualizer.
- **M6: Deployment** — Dockerization, CI/CD, hosting, multi-region, chaos engineering.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.4.4 |
| Transport | Raw WebSocketHandler on Jetty 12 |
| Protocol | Compact text codec shared by server and bot client |
| Build | Gradle Kotlin DSL |
| Testing | JUnit 5, full suite green including long-run gate |

## Next Milestone Goals

- Define the post-v2 milestone scope instead of carrying active work in ad hoc backlog.
- Replace the temporary WebSocket caps with durable registration / admission policy (`999.1`).
- Decide offspring agency and the M5 flower-rendering fallback (`999.2`).
- Choose whether the next milestone prioritizes scale engineering (M4) or observability / visualization (M5).

## Historical Context

<details>
<summary>Archived internal milestone artifacts</summary>

- `.planning/milestones/v1.0-ROADMAP.md`
- `.planning/milestones/v1.0-MILESTONE-AUDIT.md`
- `.planning/milestones/v2.0-ROADMAP.md`
- `.planning/milestones/v2.0-REQUIREMENTS.md`
- `.planning/milestones/v2.0-MILESTONE-AUDIT.md`

</details>
