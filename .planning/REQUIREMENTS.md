# Requirements — v2.0: Combination & Emergence

## Goal

Simple entities combine into complex organisms when conditions align. Complex life emerges from simple rules.

## Requirements

| ID | Requirement | Priority | Phase | Status |
|----|------------|----------|-------|--------|
| R01 | Bonding rules configurable via application.yml or dedicated config | Must | 11 | Pending |
| R02 | At least two bonding conditions (e.g., proximity + energy threshold) | Must | 11 | Pending |
| R03 | Bonding events observable in tick output | Should | 11 | Pending |
| R04 | Composite entity representation on Cell[][] grid | Must | 12 | Pending |
| R05 | Shared energy pool across composite members | Must | 12 | Pending |
| R06 | Composites move as a coordinated unit | Must | 12 | Pending |
| R07 | Member death triggers composite dissolution or degradation | Must | 12 | Pending |
| R08 | Metabolism rates differ by entity type and composite size | Must | 13 | Pending |
| R09 | Starvation mechanic with configurable thresholds | Must | 13 | Pending |
| R10 | Reproduction gated by energy surplus | Should | 13 | Pending |
| R11 | Cell.nutrientLevel activated (resolves tech debt from phase 06) | Should | 13 | Pending |
| R12 | At least two new environmental effects beyond overcrowding | Must | 14 | Pending |
| R13 | Environmental effects use Cell flags system | Must | 14 | Pending |
| R14 | Spatial propagation of effects across ticks | Should | 14 | Pending |
| R15 | Deterministic seed test for composite formation | Must | 15 | Pending |
| R16 | Population dynamics test with metabolism + environment | Must | 15 | Pending |
| R17 | At least one emergent pattern documented | Should | 15 | Pending |
| R18 | Load test with composites — no regression from v1.0 baseline | Must | 15 | Pending |
| R19 | All v1.0 tests still pass (no regressions) | Must | 15 | Pending |

## Traceability

| Phase | Requirements |
|-------|-------------|
| 11 — Bonding Rules Engine | R01, R02, R03 |
| 12 — Composite Entities | R04, R05, R06, R07 |
| 13 — Energy & Metabolism | R08, R09, R10, R11 |
| 14 — Environmental Rules | R12, R13, R14 |
| 15 — Emergent Behavior Tests | R15, R16, R17, R18, R19 |
