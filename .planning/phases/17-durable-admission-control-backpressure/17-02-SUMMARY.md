---
phase: 17
plan: 02
subsystem: codec
tags: [codec, wire-protocol, register-frame, sync-frame, resume-token, tdd]
dependency_graph:
  requires: []
  provides:
    - Frame.RegisterFrame(char, Optional<String>) with r:-sentinel resume-token slot
    - Frame.SyncFrame(String, Optional<String>, List<ActiveEffect>) with token slot
    - PerceptionCodec round-trips r|<type>|r:<16hex> and S|<id>|r:<16hex>[|<effects>]
    - ParseCursor.setIndex() for non-consuming sentinel peek
  affects:
    - Plan 05 (ResumeTokenRegistry) — must mint tokens via String.format("r:%016x", n)
    - Plan 07 (WorldWebSocketHandler retokening) — SyncFrame now has 3-arg ctor
    - Plan 09 (BotClient reconnect) — RegisterFrame now has 2-arg ctor with token
tech_stack:
  added: []
  patterns:
    - Non-consuming cursor peek via snapshot/restore (ParseCursor.setIndex)
    - Optional sentinel disambiguation (peekStartsWithSentinel) — sole r: check
key_files:
  created:
    - src/test/java/com/paralife/codec/RegisterFrameResumeTokenTest.java
    - src/test/java/com/paralife/codec/SyncFrameResumeTokenTest.java
  modified:
    - src/main/java/com/paralife/codec/Frame.java
    - src/main/java/com/paralife/codec/PerceptionCodec.java
    - src/main/java/com/paralife/codec/ParseCursor.java
decisions:
  - "Resume-token sentinel is the literal two-char sequence 'r:' — sole disambiguator in parseSync; no content-shape heuristics"
  - "ParseCursor.setIndex() added as minimal accessor to support non-consuming peekStartsWithSentinel; restores cursor in finally block"
  - "Convenience constructors (RegisterFrame(char) and SyncFrame(String, List)) preserve all existing call sites without edits"
metrics:
  duration: "~15 minutes"
  completed: "2026-04-27"
  tasks_completed: 1
  tasks_total: 1
  files_changed: 5
---

# Phase 17 Plan 02: Codec Resume-Token Slot Summary

**One-liner:** Compact-text codec extended with `r:`-sentinel resume-token slot on `RegisterFrame` and `SyncFrame` via deterministic two-char disambiguator; all existing round-trip vectors preserved.

## What Was Built

Extended the codec layer to support Phase 17's resume-token wire shapes without breaking any existing protocol vectors:

- **`Frame.RegisterFrame`** — gains `Optional<String> resumeToken` as second record component. Single-arg convenience ctor `RegisterFrame(char)` delegates to `this(entityType, Optional.empty())`, keeping all existing call sites compiling without modification.

- **`Frame.SyncFrame`** — gains `Optional<String> resumeToken` as second record component (before effects). Two-arg convenience ctor `SyncFrame(String, List<ActiveEffect>)` delegates to `this(entityId, Optional.empty(), effects)`.

- **`ParseCursor`** — gains `setIndex(int)` for non-consuming sentinel peek. Used exclusively by `peekStartsWithSentinel`; always called in a `finally` block to guarantee cursor state is restored even on early-return false paths.

- **`PerceptionCodec`** — four method updates:
  - `parseRegister`: removed strict `!c.atEnd()` throw; reads optional `|<r:token>` slot. Token that doesn't start with `r:` → `CodecException` with the literal `r:` in the message.
  - `encodeRegister`: emits `|<token>` via `resumeToken().ifPresent`.
  - `parseSync`: disambiguates token vs effects slot by `peekStartsWithSentinel(c, "r:")`. Token path reads up to next `|` or end; optional effects follow. Non-token path delegates to existing `parseEffectList` unchanged.
  - `encodeSync`: emits `|<token>` (if present) before `|<effects>` (if non-empty).
  - New `RESUME_TOKEN_SENTINEL = "r:"` constant (declared once, used in parse and encode).
  - New `peekStartsWithSentinel(ParseCursor, String)` helper (non-consuming, snapshot/restore).

## Locked Token Format (for Plan 05)

**Plan 05 (`ResumeTokenRegistry`) MUST mint tokens as:**

```java
String.format("r:%016x", ThreadLocalRandom.current().nextLong())
```

This produces exactly 18 chars on wire: literal `r:` followed by 16 lowercase hex digits. The codec accepts any `r:`-prefixed string (content opaque at codec layer), but the registry and its tests should use this exact format for canonical round-trip compliance.

## Test Coverage

**`RegisterFrameResumeTokenTest`** (8 tests):
- `parseRegisterWithoutTokenBackwardCompat` — `r|C` → `RegisterFrame('C', empty)`
- `parseRegisterWithToken` — `r|C|r:0a1b2c3d4e5f6789` → correct token
- `encodeRegisterWithoutToken` — encodes to `r|C`
- `encodeRegisterWithToken` — encodes to `r|C|r:0a1b2c3d4e5f6789`
- `roundTripWithToken` — all three species (C/M/S) with token
- `parseRegisterEmptyTokenAfterPipe` — `r|C|` throws
- `parseRegisterTokenMissingSentinel` — `r|C|deadbeef...` throws with `r:` in message
- `parseRegisterUnknownTypeWithToken` — `r|X|r:...` throws

**`SyncFrameResumeTokenTest`** (10 tests):
- `parseSyncEntityOnly` — entity-only backward compat
- `parseSyncEntityAndEffects_legacyShapeF` — `f`-prefixed second slot not treated as token
- `parseSyncEntityAndEffects_vector10Shape` — V10 schema vector `S:1Fg8,I:1Ef0` (effects starting with `S:`) parses correctly with no token
- `parseSyncEntityAndToken` — token, no effects
- `parseSyncEntityTokenAndEffects` — token + effects
- `encodeRoundTripAllFour` — all four cardinalities round-trip
- `parseSyncEmptyEntity` — `S|` throws
- `parseSyncEmptyTokenSlot` — `S|abc||...` throws
- `disambiguatorIgnoresFPrefixedEffects` — `f0a1b...` not treated as token
- `peekIsNonConsuming` — confirms sentinel peek does not corrupt cursor state

All 18 new tests pass. All pre-existing codec tests pass (including round-trip vector V10 `S|7A|S:1Fg8,I:1Ef0`).

## Deviations from Plan

None — plan executed exactly as written.

The plan noted to "read `ParseCursor.java` first to confirm `readUntil('|', false)` terminates safely at end-of-input." Confirmed: `readUntil` loops while `idx < source.length() && char != delim`, so it safely returns the substring up to end-of-input if the delimiter is not found. No change to `readUntil` was needed.

The plan also noted to add `setIndex`/`index` accessors if absent. `index()` already existed; only `setIndex()` was added.

## Backward Compatibility

All six legacy wire shapes round-trip unchanged:
- `r|C`, `r|M`, `r|S` — plain register (no token)
- `S|<id>` — sync without effects or token
- `S|<id>|<effects>` — sync with effects, no token (e.g. V10 `S|7A|S:1Fg8,I:1Ef0`)
- `S|<id>|f<...>` — TickFrame-style `f` prefix in effects (rejected by effect parser as expected, not misrouted to token path)

## Threat Surface

No new network endpoints or auth paths introduced. The codec is a pure parsing layer. Relevant mitigations per plan threat model:

- **T-17-04** (memory exhaustion via huge token): Bounded by Jetty's 64KB WebSocket message limit. No additional cap at codec layer.
- **T-17-codec-malformed** (malformed input crashes server): Empty token slot and missing-sentinel both throw `CodecException`. Disambiguator is deterministic.
- **T-17-spoof** (token spoofing): Codec accepts any `r:`-prefixed string; registry validates contents server-side per Plan 05.

## Self-Check

PASSED — verified:
- `Frame.java` contains `Optional<String> resumeToken` in both records (grep count: 2)
- `PerceptionCodec.java` does NOT contain `Register frame has trailing bytes` (grep count: 0)
- `PerceptionCodec.java` contains `RESUME_TOKEN_SENTINEL = "r:"` (grep count: 3 — declaration + 2 uses)
- `PerceptionCodec.java` does NOT contain `Character.isDigit` (grep count: 0)
- `PerceptionCodec.java` `encodeSync` calls `resumeToken().ifPresent` (grep count: 2)
- `RegisterFrameResumeTokenTest.java` has 8 `@Test` methods
- `SyncFrameResumeTokenTest.java` has 10 `@Test` methods
- `./gradlew test -PincludeLong=false` exits 0 (full suite)
- Commits exist: RED `a501268`, GREEN `c569fc4`
