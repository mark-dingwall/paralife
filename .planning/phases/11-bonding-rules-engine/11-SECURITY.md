---
phase: 11
slug: bonding-rules-engine
status: verified
threats_open: 0
asvs_level: 1
created: 2026-04-13
---

# Phase 11 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Config -> SimulationEngine | Configuration values from application.yml cross into physics logic | BondingConfig record (int, double, double) |
| SimulationEngine -> TickBroadcaster | bondCount integer crosses from engine to WebSocket layer | int bondCount (non-sensitive) |
| Grid state -> PerceptionBroadcaster | BondedPair entity data serialized to client perception | Entity type strings, entity IDs (server-generated) |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-11-01 | Tampering | BondingConfig | mitigate | Compact constructor validates threshold >= 0, probability in [0,1], defense in [0,1] — `BondingConfig.java:19-26`. 12 unit tests cover validation. | closed |
| T-11-02 | Denial of Service | processInteractions | accept | O(N) scan per tick, same complexity as prior processCombat. No amplification vector beyond existing entity count. Grid size bounded by GridConfig. | closed |
| T-11-03 | Information Disclosure | BondedPair.id() | accept | Bond ID is concatenation of two entity IDs (e.g., "cat+spo"). No PII. Entity IDs are server-generated counter-based strings. | closed |
| T-11-04 | Information Disclosure | PerceptionBroadcaster.cellToView | accept | BondedPair exposes primaryType and secondaryType in perception. Intentional — bots need to see neighboring cell occupants. No PII involved. | closed |
| T-11-05 | Tampering | Messages.Tick.bondCount | accept | bondCount is server-generated, read-only for clients. Broadcast via WebSocket push. No client input path to manipulate this value. | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-01 | T-11-02 | O(N) complexity matches existing combat scan — no new amplification vector | gsd-security-audit | 2026-04-13 |
| AR-02 | T-11-03 | Entity IDs are server-generated, contain no PII | gsd-security-audit | 2026-04-13 |
| AR-03 | T-11-04 | Type exposure is intentional for bot perception protocol | gsd-security-audit | 2026-04-13 |
| AR-04 | T-11-05 | Server-generated value with no client write path | gsd-security-audit | 2026-04-13 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-04-13 | 5 | 5 | 0 | gsd-secure-phase |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-04-13
