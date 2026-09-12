# Death diagnostics

## Why

Death diagnostics provide an opt-in lifecycle trace for understanding why individual grid
entities die. The contract is deterministic bookkeeping; population-level distributions remain
observational because they vary with simulation tuning and emergence.

## What changes / Impact

This document makes the existing `DeathDiagnostics`, `LiveEntityRegistry`, `DeathFinalizer`, and
composite-member cleanup contract explicit. It does not change the wire protocol or simulation
mechanics.

## Lifecycle contract

- **Birth:** WHEN `LiveEntityRegistry` accepts a new grid entity id, THE SYSTEM SHALL record that
  id's birth at the current tick.
- **First lethal hint:** WHEN an energy sink first identifies that an entity will reach zero energy,
  THE SYSTEM SHALL retain that cause and pre-hit energy until terminal cleanup and SHALL ignore
  later lethal hints for the same lifecycle.
- **Starvation fallback:** WHEN a death is finalised without a retained lethal hint, THE SYSTEM
  SHALL attribute the death to `STARVATION` and record no pre-hit energy.
- **Lifespan record:** WHEN a death is finalised, THE SYSTEM SHALL emit one `DEATH-TRACE` lifecycle
  record containing the id, type, retained cause, current tick, pre-hit energy, and lifespan in
  ticks from the recorded birth; an id without a recorded birth SHALL use `-1` for lifespan.
- **Death cleanup:** WHEN a particle, bonded pair, or composite member is finalised as dead, THE
  SYSTEM SHALL record its death before `LiveEntityRegistry.unregister` silently forgets its
  lifecycle state.
- **Non-death forget:** WHEN an id is unregistered for a transition, disconnect, rollback, or other
  non-death exit, THE SYSTEM SHALL forget its birth and lethal-hint state without emitting a death
  lifecycle record or death meter update. A later registration of the same id starts a fresh
  lifecycle.

Each finalised death also exposes a `paralife.diag.deaths` meter series tagged with the lowercase
cause and the recorded entity type. The in-memory histogram and all meter counter totals are
**observe-only emergence signals**: default-suite tests SHALL NOT assert their values, shares,
rates, bucket composition, or any threshold derived from them.

## Assumptions / Open questions

Diagnostics remain absent unless `paralife.diagnostics.death-trace.enabled=true`. No open question
changes this lifecycle contract.

## Non-Goals

Population balance, death-cause proportions, lifespan distributions, and tuning conclusions are
outside this mechanism contract. They belong to observe-only runs or the opt-in slow emergence
suite.

## Readiness

**GO** — the clauses pin local lifecycle transformations without pinning emergent aggregates.
