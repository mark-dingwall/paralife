# Requirements: Paralife v3.0 Scale Engineering

**Defined:** 2026-04-22
**Core Value:** Emergent spatial behaviour from simple local rules — a testbed for evolving entity intelligence

## Goal

Prove the architecture handles large-scale externally driven load without losing simulation correctness, operational control, or repeatability.

## v3.0 Requirements

### Admission Control

- [x] **SCALE-01**: Server admission control replaces the temporary `max-active-entities` stopgap with a durable world-level policy that explains register and respawn rejection reasons.
- [x] **SCALE-02**: Overload and slow-client paths apply explicit backpressure or shedding rules without causing unbounded tick drift or silent session churn.

### Load Generation

- [ ] **SCALE-03**: Operators can launch a standalone external load harness that scales beyond `BotRunner`'s single-process 100-bot ceiling.
- [ ] **SCALE-04**: Each harness instance identifies itself so sessions, failures, and throughput can be attributed per harness in logs and metrics.
- [ ] **SCALE-05**: `BotRunner` remains the supported local operator path for small-N runs while the harness owns large-scale traffic.

### World Scaling

- [ ] **SCALE-06**: High-density runs use placement behavior that avoids pathological collisions with rocks and already-occupied cells.
- [ ] **SCALE-07**: World execution gains a partition-aware or equivalently decomposed scale path that can grow without changing observed simulation semantics.

### Transport & Runtime Efficiency

- [ ] **SCALE-08**: High bot-count runs reduce socket or process overhead through connection multiplexing or an equivalent transport-level scale strategy.
- [ ] **SCALE-09**: Runtime tuning for virtual threads and the compact protocol is measured and documented from real benchmark profiles rather than guesswork.

### Benchmark Gate

- [ ] **SCALE-10**: M4 closes with repeatable 100, 500, and 1000+ bot benchmark reports covering tick drift, session stability, throughput, rejection counts, and major failure modes.

## Future Requirements

- **OBS-01**: Global observer tooling and richer metrics visualization belong to M5, not this scale milestone.
- **OBS-02**: Offspring bot-agency / flower-rendering semantics remain deferred until the visualizer and observer work is in scope.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Full observability dashboard | M5 owns richer visualization and operator UX |
| Deployment / multi-region / CI-CD | M6 owns real deployment concerns |
| Offspring agency / flower visualizer fallback | Deferred backlog `999.2`; not required to prove scale behavior |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| SCALE-01 | Phase 17 | Satisfied (2026-04-28) |
| SCALE-02 | Phase 17 | Satisfied (2026-04-28) |
| SCALE-03 | Phase 18 | Pending |
| SCALE-04 | Phase 18 | Pending |
| SCALE-05 | Phase 18 | Pending |
| SCALE-06 | Phase 19 | Pending |
| SCALE-07 | Phase 19 | Pending |
| SCALE-08 | Phase 20 | Pending |
| SCALE-09 | Phase 20 | Pending |
| SCALE-10 | Phase 21 | Pending |

**Coverage:**
- v3.0 requirements: 10 total
- Mapped to phases: 10
- Unmapped: 0

---
*Requirements defined: 2026-04-22*
*Last updated: 2026-04-28 — SCALE-01 / SCALE-02 marked satisfied after Phase 17 verification (passed; see `phases/17-durable-admission-control-backpressure/17-VERIFICATION.md`)*
