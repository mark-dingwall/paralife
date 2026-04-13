---
phase: 12-composite-entities
auditor: gsd-security-auditor
asvs_level: 1
block_on: open_threats
generated: 2026-04-14
threats_open: 0
threats_total: 13
---

# Phase 12 Security Audit

## Result: SECURED

**Threats Closed:** 13/13
**ASVS Level:** 1
**Block On:** open_threats

---

## Threat Verification

| Threat ID | Category | Disposition | Status | Evidence |
|-----------|----------|-------------|--------|----------|
| T-12-01 | Information Disclosure | accept | CLOSED | Accepted risk logged below |
| T-12-02 | Tampering | mitigate | CLOSED | ActionResolver.java:571-575, 721-732 — `.limit(3)` + `Direction.fromString()` filter |
| T-12-03 | DoS | mitigate | CLOSED | ActionResolver.java:571-575 — `.limit(3)` cap on oversized rankedPreferences list |
| T-12-04 | Tampering | accept | CLOSED | Accepted risk logged below |
| T-12-05 | DoS | accept | CLOSED | Accepted risk logged below |
| T-12-06 | Tampering | mitigate | CLOSED | ActionResolver.java:729-732 — `prefs.stream().limit(3).filter(s -> Direction.fromString(s) != null).toList()`, comment cites T-12-06 |
| T-12-07 | Spoofing | accept | CLOSED | Accepted risk logged below |
| T-12-08 | DoS | mitigate | CLOSED | ActionResolver.java:571-575 — prefs capped to 3 via `.limit(3)`, comment cites T-12-08 |
| T-12-09 | Information Disclosure | accept | CLOSED | Accepted risk logged below |
| T-12-10 | DoS | accept | CLOSED | Accepted risk logged below |
| T-12-11 | Information Disclosure | accept | CLOSED | Accepted risk logged below |
| T-12-12 | DoS | mitigate | CLOSED | PerceptionBroadcaster.java:63-87 — `compositePerceptionCache` HashMap, built once per compositeId per tick; comment cites T-12-12 |
| T-12-13 | — | accept | CLOSED | Accepted risk logged below |

---

## Mitigate Threats — Verification Detail

### T-12-02 / T-12-03 / T-12-06 / T-12-08 — rankedPreferences input validation

Two code paths enforce the cap and direction validation:

**Path A — CompositeAction processing in resolveCompositeMovements (lines 569-575):**
```
prefs = prefs.stream().limit(3).filter(s -> Direction.fromString(s) != null).toList();
```
Comment on line 571: `// Cap at 3 entries, filter invalid (T-12-06, T-12-08)`

**Path B — extractRankedPreferences(Messages.CompositeAction) helper (lines 721-733):**
```
return prefs.stream()
        .limit(3)
        .filter(s -> Direction.fromString(s) != null)
        .toList();
```
Comment on line 729: `// Cap at 3 entries (T-12-06, T-12-08), filter invalid directions`

Both paths are reached from `resolveCompositeMovements`. The ranked preferences map (`pendingRankedPreferences`) is populated in `queueCompositeAction` with the raw client-supplied list; validation is applied at consumption time during tick resolution, not at queue time. This is consistent with the mitigation plan (validation in ActionResolver).

`Direction.fromString()` returns null for any unrecognised string; nulls are eliminated by the filter before the list is used in vote counting. An empty list after filtering causes the LOCOMOTOR vote to abstain, which is safe.

### T-12-12 — PerceptionBroadcaster memoization

`buildStitchedPerception` is called at most once per compositeId per tick via:
```java
if (!compositePerceptionCache.containsKey(compositeId)) {
    var stitched = buildStitchedPerception(event.tickNumber(), cm, bot);
    compositePerceptionCache.put(compositeId, stitched);
}
```
The cache is a local `HashMap` created fresh in `onTick`, so it has no cross-tick state. Blind composites (null return from `buildStitchedPerception`) are stored as null in the cache and cause perception to be skipped for all members of that composite — cost is bounded to one null lookup per member per tick.

---

## Accepted Risks Log

| Threat ID | Category | Component | Rationale |
|-----------|----------|-----------|-----------|
| T-12-01 | Information Disclosure | CompositeRegistry.CompositeState.sharedPoolEnergy | Internal server-side state only. Energy values broadcast via controlled Perception/CompositePerception fields. No raw internal state exposed to clients. |
| T-12-04 | Tampering | SimulationEngine.processInteractions composite formation | Formation is entirely server-driven. No client input influences formation decisions. Snapshot+deferred-write pattern prevents race conditions within the tick pipeline. |
| T-12-05 | DoS | CompositeEnergyDistributor | Iterates all composites each tick. Bounded by grid size (max composites = grid_cells / 2). O(n) where n = total composite members. Same cost profile as existing `processEnergyDecay`. |
| T-12-07 | Spoofing | ActionResolver.queueAction | Bot session identity validated by WorldWebSocketHandler before any action reaches ActionResolver. No additional spoofing surface introduced by composite action routing. |
| T-12-09 | Information Disclosure | SimulationEngine.processDeaths dissolution | Dissolution events are observable via tick broadcast (entities disappear from grid). Intended game-visible behaviour. No PII or server internals exposed. |
| T-12-10 | DoS | SimulationEngine.dissolveToParticles | Dissolution creates at most N particles from N members. No entity-count amplification — total count stays the same or decreases. |
| T-12-11 | Information Disclosure | PerceptionBroadcaster.stitchedPerception | Stitched perception reveals more grid area than an individual 5x5 view, but this is intentional game design rewarding SENSOR role inclusion. No PII or server internals exposed. |
| T-12-13 | — | CompositeIntegrationTest | Test code only. No production attack surface. |

---

## Unregistered Threat Flags

None. All threat flags referenced in SUMMARY.md files (T-12-06, T-12-08 cited in 12-03-SUMMARY.md) map to registered threats in the threat register.
