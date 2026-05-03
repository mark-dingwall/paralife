---
source: 19-MULTI-REVIEW-pass3.md
validated_at: 2026-05-04
validated_by: claude (subagent line-by-line check vs current code, post pass-2 commits)
status: validated — handoff to separate execution agent
basis_for: P22 close-out plan (this file persisted to make the validated finding set durable; full transcript reproduction cost ~63k tokens)
---

# Phase 19.5 Pass-3 Multi-Review — Validated Findings

Subagent verified each pass-3 claim line-by-line against current code. Result: **3 real fixes (F1/F2/F3) + 1 defensible partial (F4)**. None touch P22 invariants — clean handoff.

## Validated finding set

| ID | Severity | What | Location |
|---|---|---|---|
| F1 | H | Composite formation queues spurious `DeathNotice` (uses `unregisterByEntity`+`register` instead of `remapEntity`) — orphans newly-remapped session | `src/main/java/com/paralife/engine/SimulationEngine.java:887-893` |
| F2 | H | `PerceptionCodec.validateEventCode` rejects `'B'` — encode emits, decode throws → bots silently miss absorbed-frame respawn | `src/main/java/com/paralife/codec/PerceptionCodec.java:685, 694` |
| F3 | M | `WorldWebSocketHandler.markDead` leaks ACTIVE resume tokens — `ResumeTokenRegistry` grows unbounded across server lifetime | `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:944-947` |
| F4 (partial) | M | Mutagen survivor-buff RNG order via CHM iteration; pass-2 rationale incomplete (covered DoT but not `randomBuff()`) | `src/main/java/com/paralife/engine/EnvironmentEngine.java:770` |

## Structural insight

F1 + F2 survived two review passes because `GoldenTraceEquivalenceTest.seedAdjacentBondingPair` doesn't register seed pairs through `BotRegistry` + `SessionRegistry` with mock sessions. Same fix unlocks regression coverage.

## Convention to adopt

Pass-2 shipped `'B'` event code without a codec round-trip test. **Every new event code in `Event.java` requires a `PerceptionCodecTest` round-trip case.**

## Files involved (out of P22 scope; for execution agent)

- F1: `src/main/java/com/paralife/engine/SimulationEngine.java:881-894`
- F2: `src/main/java/com/paralife/codec/PerceptionCodec.java:683-697`
- F3: `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:944-947`
- F4: `src/main/java/com/paralife/engine/EnvironmentEngine.java:716-781`
- Test seam: `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java:316-333`

## Source

Verbatim cross-AI review report (claude / gemini / codex / opencode synthesized) lives at `19-MULTI-REVIEW-pass3.md` in this directory. This file is the validation overlay — the durable record that the subagent's check confirmed F1/F2/F3 and partial F4 against current code.
