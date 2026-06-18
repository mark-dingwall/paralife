---
phase: 20
slug: connection-multiplexing-runtime-tuning
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-11
---

# Phase 20 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> Phase 20 is predominantly documentation + launch-only operator config tuning;
> the WS:entity 1:1 model is non-negotiable and tuning reduces per-connection cost
> without collapsing connections.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| operator → bootstrap.md / 20-RUNTIME.md | external async-profiler download URLs; doc-only JVM-flag recipes | dev-tool binaries; no server runtime surface |
| operator → application.yml | yaml override surface; type-checked by Spring binding + record compact-ctor | non-secret runtime tuning knobs |
| client → Jetty WS upgrade | Jetty parses frames; maxFrameSize cap enforced at Jetty layer | inbound WS frames (bounded) |
| codec → wire | wire format LOCKED (15-SCHEMA.md); byte-for-byte equivalence enforced | perception/action frames |
| operator → profiles/ (JFR + metric sidecars) | committed binary/JSON artifacts | gauge values + UTC timestamps + JVM-internal events; no PII/secrets |
| contributor → source comments / README / CLAUDE.md | comment + doc changes codifying WS:entity 1:1 design | non-secret architectural rationale |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-20-S | S (Spoofing) | async-profiler binary | accept | official GitHub release; SHA-pin = recommendation in `tools/async-profiler-bootstrap.md`, dev-tool not server-side | closed |
| T-20-V5 | T (Tampering — invalid config / bounds bypass) | JettyRuntimeConfig + AppRuntimeConfig compact-ctors; PerceptionCodec MAX_S_ENTRIES/MAX_V_ENTRIES | mitigate | lower-bound checks with property-key errors (`JettyRuntimeConfig.java:72-101`, `AppRuntimeConfig.java:73-83,111-119`); codec bounds unchanged (`PerceptionCodec.java:29,35`; varbase64 cap `:919,:931,:943`) | closed |
| T-20-DOS-1 | D (DoS — giant frames / profiles growth) | JettyRuntimeConfig.maxFrameSize/maxBinary/maxText; profiles/ dir | mitigate | preserved 65536 default cap (`JettyRuntimeConfig.java:46`, `application.yml:61`); ctor floor ≥1024; profiles ≤10 MB/file ≤50 MB total (`profiles/README.md`) | closed |
| T-20-V7 | I (Information disclosure — yaml/JFR/sidecars/docs) | new yaml keys; profiles/*.jfr; metrics-*.json; docs | mitigate+accept | non-secret tuning knobs; sidecars carry only Micrometer gauge values + UTC timestamps; JFR = JVM-internal events; no PII; reviewed before commit | closed |
| T-20-DOS-2 | D (DoS — pinned-VT exhaustion) | OutboundSender synchronized(session) writers | accept | JFR triage (`103a615`) found 0 `jdk.VirtualThreadPinned` events; backlog-handoff to Phase 999.6 documented, not silent | closed |
| T-20-DOC-DRIFT | T (Tampering — WS:entity 1:1 design drift without ADR) | source comments + CLAUDE.md + README.md | mitigate | three-place D-02 codification: `README.md:39`, `CLAUDE.md:152`, `WorldWebSocketHandler.java:320`, `OutboundSender.java:136` — all cite D-21 ADR requirement | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-20-1 | T-20-S | async-profiler is a developer-only profiling tool, not part of server runtime or any production path. SHA verification of the downloaded GitHub release is recommended (`tools/async-profiler-bootstrap.md`) but not mechanically enforced. Risk confined to local developer environment; server posture unaffected. | Phase 20 | 2026-06-11 |
| AR-20-2 | T-20-DOS-2 | JFR triage (Plan 5, `103a615` active-50xfood baseline) found 0 `jdk.VirtualThreadPinned` events across the 1000-bot scenario. Theoretical exhaustion via `synchronized(session)` writers exists but was not observable under load. Phase 999.6 (`vt-pinning-reentrantlock-conversion`) is the documented backlog item if future JFR shows dominant pinning. JFR evidence committed under `profiles/`. | Phase 999.6 | 2026-06-11 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-06-11 | 6 | 6 | 0 | gsd-security-auditor (Claude Sonnet 4.6) |

### Audit Notes

- `PerceptionCodec.java` confirmed unchanged vs canonical baseline `62c1b44` per Plan 5 null-result. `MAX_S_ENTRIES = 256` / `MAX_V_ENTRIES = 32` present and enforced in the decode path.
- `JettyRuntimeConfig` compact-ctor bounds are intentionally floors (not ceilings) — the T-20-DOS-1 DoS control is the preserved 65536 default, not the minimum bound. Knobs are launch-only operator surfaces, not attacker-controlled.
- `AppRuntimeConfig` fields are tagged `[reserved — no effect in Phase 20]` (Pass-2 Concern #7); bound checks present regardless as defensive posture for future consumers.
- D-02 three-place codification spans 4 files (README.md, CLAUDE.md, WorldWebSocketHandler.java, OutboundSender.java) — consistent with the `20-06-PLAN.md` `grep -lE "WS:entity 1:1"` acceptance criterion.
- No SUMMARY.md `## Threat Flags` entries; phase used inline `<threat_model>` blocks per plan. `20-05` / `20-06` Threat Surface Scans confirm no new threats at execution time.

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-06-11
