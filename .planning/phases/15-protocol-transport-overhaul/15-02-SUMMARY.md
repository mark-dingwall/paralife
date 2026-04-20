---
phase: 15-protocol-transport-overhaul
plan: 02
subsystem: codec
tags: [codec, scaffold, red-tests, sealed-hierarchy, wave-0]

requires:
  - phase: 15-protocol-transport-overhaul
    plan: 01
    provides: R20-R29 requirement IDs and Vector 9 corrected SCHEMA §10
provides:
  - com.paralife.codec package — pure-Java module, zero Spring / Jackson imports
  - Frame sealed interface (RegisterFrame / SyncFrame / TickFrame / ActionFrame / ErrorFrame)
  - Coord sealed interface (Numpad / Relative / Absolute) — no 6-char extended form
  - KindData sealed interface (Simple / RockSolo / RockRun RLE 1..63)
  - CellEntry, Event, ActiveEffect, StateChange, PoolSnapshot, RosterMember records
  - Base64Codec public 64-char alphabet + encode/decode digit helpers
  - CodecException runtime exception (message / message+cause ctors)
  - ParseCursor package-private LL(1) single-pass parser state
  - PerceptionCodec stub (encode/decode throw UnsupportedOperationException) — RED marker
  - Public DoS bound constants MAX_S_ENTRIES=256, MAX_V_ENTRIES=32 (per SCHEMA §12)
  - PerceptionCodecRoundTripTest with 13 SCHEMA §10 vectors (Vector 9 = v+F-3L5) in RED state
  - TickFrame sensorRadius=0 minimal-form sentinel documented in javadoc + ctor-enforced
affects: [15-03, 15-04, 15-05, 15-06, 15-07, 15-08, 15-09, 15-10, 15-11]

tech-stack:
  added:
    - "com.paralife.codec (pure Java, no framework deps)"
  patterns:
    - "Sealed interface + nested record hierarchy (mirrors Messages.java / Entity.java)"
    - "Utility-class idiom (private ctor + static methods) for Base64Codec and PerceptionCodec"
    - "Canonical record constructors as validation boundary (range checks throw IAE)"
    - "Parameterized test with @MethodSource as acceptance oracle (wire frame round-trip)"
    - "RED stub pattern — UnsupportedOperationException to gate downstream impl plan"

key-files:
  created:
    - src/main/java/com/paralife/codec/Base64Codec.java
    - src/main/java/com/paralife/codec/CodecException.java
    - src/main/java/com/paralife/codec/ParseCursor.java
    - src/main/java/com/paralife/codec/Frame.java
    - src/main/java/com/paralife/codec/Coord.java
    - src/main/java/com/paralife/codec/KindData.java
    - src/main/java/com/paralife/codec/CellEntry.java
    - src/main/java/com/paralife/codec/Event.java
    - src/main/java/com/paralife/codec/ActiveEffect.java
    - src/main/java/com/paralife/codec/StateChange.java
    - src/main/java/com/paralife/codec/PoolSnapshot.java
    - src/main/java/com/paralife/codec/RosterMember.java
    - src/main/java/com/paralife/codec/PerceptionCodec.java
    - src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java
  modified: []

key-decisions:
  - "No 6-char extended relative coord form — Coord.Relative is exactly 4 chars ([+-]X[+-]Y) with ±63 range, per SCHEMA §2."
  - "TickFrame records enforce the minimal-form invariant in the canonical ctor: sensorRadius=0 implies empty cells/change/effects/pool/roster — only events allowed. Prevents silent wire-ambiguous frames from ever being constructed."
  - "Event is a single record with optional coord + magnitude (orthogonal variance), not a sealed sub-hierarchy. Rationale: the §8.4 table varies on coord/magnitude presence rather than on structurally different shapes, so one record with OptionalInt + Optional<Coord> is simpler than a sealed hierarchy of 10+ variants."
  - "Base64Codec.encodeDigit / decodeDigit throw CodecException (wire-level) rather than IllegalArgumentException (domain). Keeps alphabet-boundary failures separable from value-range failures at the parser boundary."
  - "ParseCursor kept package-private — intentionally not public API. Mutable cursor threaded down the parse tree is an implementation detail of the codec package."

requirements-completed: [R20, R21]

metrics:
  duration: 10min
  started: 2026-04-20T01:30Z
  completed: 2026-04-20T01:41Z
  tasks: 3
  files_created: 14
  files_modified: 0
  tests_added: 1 class (13 parameterized vectors)
  tests_passing: 0 (intentional RED — turns GREEN in 15-05)
---

# Phase 15 Plan 02: Codec Scaffold + RED Round-Trip Test Summary

**Created the `com.paralife.codec` pure-Java package with 13 source files (sealed Frame / Coord / KindData hierarchies + supporting records + PerceptionCodec stub) and a 13-vector round-trip test seeded RED from SCHEMA §10 — downstream plans now compile against stable interfaces while plan 15-05 supplies the codec body.**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-04-20T01:30Z
- **Completed:** 2026-04-20T01:41Z
- **Tasks:** 3 (all `type="auto" tdd="true"`, committed separately)
- **Files created:** 14 (13 main + 1 test)
- **Files modified:** 0

## Accomplishments

### Task 1 — Base64Codec + CodecException + ParseCursor (commit `b4670fd`)

- `Base64Codec.ALPHABET` matches SCHEMA §1 authoritative literal exactly: `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-` (grep count = 1).
- `INT_TO_CHAR[64]` and `CHAR_TO_INT[128]` populated in a static initializer; unknown chars sentinel `-1`.
- `encodeDigit(int)` / `decodeDigit(char)` helpers throw `CodecException` for out-of-range / unknown chars.
- `CodecException` extends `RuntimeException` with message + message/cause ctors — maps to E|400 at the server boundary.
- `ParseCursor` is package-private (no `public` modifier), exposes mutable `index`, `peek`, `next`, `expect`, `readUntil`, `readRun`, `remaining`, `atEnd`. LL(1) single-pass contract per SCHEMA §12.
- `./gradlew compileJava` succeeded; `grep -rE "org\.springframework|com\.fasterxml\.jackson" src/main/java/com/paralife/codec/` returned nothing.

### Task 2 — Frame sealed hierarchy + supporting records + PerceptionCodec stub (commit `4d50cb7`)

- `Frame` sealed interface with single-line `permits Frame.RegisterFrame, Frame.SyncFrame, Frame.TickFrame, Frame.ActionFrame, Frame.ErrorFrame`.
- `Coord` sealed interface with `Numpad(char digit)`, `Relative(int dx, int dy)` (±63), `Absolute(int x, int y)` (0..4095). NO 6-char extended form — all relative coords are exactly 4 wire chars per SCHEMA §2.
- `KindData` sealed interface with `Simple(char code)`, `RockSolo()`, `RockRun(char direction, int additionalCount)` with direction ∈ numpad \ {5} and additionalCount ∈ 1..63.
- `CellEntry` record with validation: `presence` ∈ {1,2,3}; presence bit 0 ⇒ kind present; presence bit 1 ⇒ envState present; state bitmasks 0..63.
- `Event` record: code ∈ {E,A,H,T,M,R,L,N,S,D}; magnitude 0..63 when present.
- `ActiveEffect` record: code ∈ {I,F,A,M,S,U}; non-negative expiryTick; Optional<int[]> ctx (FLEEING carries {x,y}).
- `StateChange` record: `(char code, Optional<String> ctx)`.
- `PoolSnapshot`, `RosterMember` records.
- `Frame.TickFrame` canonical ctor documents `sensorRadius=0` as the minimal-form sentinel (§6.3.2) — javadoc has 3 "minimal form" mentions. The ctor enforces: if `sensorRadius == 0` then cells, change, effects, pool, roster must be empty/absent (only events allowed). `isMinimal()` convenience method exposes the flag for downstream readers.
- `PerceptionCodec.encode(Frame)` and `decode(String)` both throw `UnsupportedOperationException("pending plan 15-05")` — explicit RED markers.
- Public constants `MAX_S_ENTRIES = 256` and `MAX_V_ENTRIES = 32` per SCHEMA §12 DoS bounds, with javadoc pointing to the schema section.
- `./gradlew compileJava` succeeded; zero Spring / Jackson imports under `src/main/java/com/paralife/codec/`.

### Task 3 — 13-vector round-trip test in RED state (commit `f35a98d`)

- `PerceptionCodecRoundTripTest` is a package-private (non-public) test class with `@ParameterizedTest(name = "[V{index}] {0}") @MethodSource("vectors")`.
- `vectors()` returns exactly one `Stream.of(...)` with all 13 SCHEMA §10 vectors as string literals. Every vector is tagged with `// V{n}` — grep count = 13.
- Vector 9 literal is `T|001|0A1B|15/80|2|fF:2E:0F03|v+F-3L5` (corrected 4-char relative form) — `grep -cF 'v+F-3L5'` = 1, `grep -cF 'v+0F-03L5'` = 0.
- Test assertion is the byte-for-byte round-trip oracle: `encode(decode(vector)) == vector`.
- Running `./gradlew test --tests 'com.paralife.codec.PerceptionCodecRoundTripTest'` produced 13 failures, all with `java.lang.UnsupportedOperationException` — confirming RED state. This is the intended TDD state; plan 15-05 turns it GREEN with no checkpoint needed.
- Test uses only JUnit 5 (`ParameterizedTest`, `MethodSource`, `DisplayName`, `assertEquals`) — no mocking, no Spring, no Jackson.

## Key Decisions

- **No 6-char extended relative coord form.** Coord.Relative is exactly 4 wire chars ([+-]X[+-]Y) with ±63 range per SCHEMA §2. This matches Vector 9's corrected literal `+F-3` = (+15, -3) decoded as sign + base64 magnitude.
- **TickFrame ctor enforces the minimal-form invariant.** `sensorRadius=0` mandates empty `cells`/`change`/`effects`/`pool`/`roster` — only `events` allowed. This prevents wire-ambiguous TickFrame instances from ever being constructed, even by future callers who misunderstand SCHEMA §6.3.2.
- **`Event` is a single record rather than a sealed hierarchy.** The §8.4 table varies on coord / magnitude presence (orthogonal), not on structurally different shapes, so one record with `Optional<Coord>` + `OptionalInt` is simpler than 10+ sealed variants.
- **`Base64Codec` / `PerceptionCodec` use `CodecException` for wire-level failures** (parser / alphabet boundary) rather than `IllegalArgumentException` (domain value range). Keeps the E|400-mapped failure mode separable from range-guard IAE thrown in record canonical ctors.
- **`ParseCursor` is package-private.** The mutable cursor threaded down the parse tree is an internal implementation detail — exposing it publicly would leak parser mechanics into callers.

## Patterns Established

- **Sealed interface + nested record hierarchy for wire types** (mirrors existing `Messages.java` / `Entity.java`, minus Jackson annotations — the codec package is pure Java by design).
- **Utility-class idiom** (`private ctor` + `static` methods) for stateless codec-adjacent helpers (`Base64Codec`, `PerceptionCodec`).
- **Canonical record constructors as the domain-validation boundary** — range / enum-membership guards throw `IllegalArgumentException`.
- **Parameterised test with `@MethodSource` as wire oracle** — `@CsvSource` would collide with commas embedded in the vector literals.
- **`UnsupportedOperationException` stubs as RED markers** — explicit gate forcing plan 15-05 to land before callers can exercise encode/decode.

## Deviations from Plan

None. Plan executed exactly as written.

Two plan-internal observations worth noting (no code impact):

1. **Success-criteria line vs frontmatter file count.** The `<success_criteria>` block says "13 files (12 source + 1 test class)"; the authoritative `files_modified` frontmatter list enumerates 13 source files + 1 test = 14. All 13 source files from the frontmatter plus the test were created. Treating the frontmatter as authoritative (it names every file individually). Noting here so a future plan reviewer does not read this as a count drift.
2. **Acceptance criteria grep on permits clause assumes single-line formatting.** The initial Frame.java had the permits clause wrapped across two lines, which broke a literal grep in the acceptance criteria but compiled cleanly. Reformatted to single-line to satisfy the criteria verbatim. No semantic change.

## Requirements Addressed

- **R20** — Codec package scaffold with sealed Frame hierarchy (delivered).
- **R21** — Round-trip test oracle seeded from SCHEMA §10 vectors in RED state (delivered).

Both requirements satisfied; plan 15-05 will close out the RED-to-GREEN transition by implementing the codec body.

## Threat Model Traceability

`T-15-01 (DoS on decode)` — partially mitigated at the type-system floor:

- Record canonical ctors reject nonsensical values (range guards in every record).
- `MAX_S_ENTRIES = 256` and `MAX_V_ENTRIES = 32` are public constants, so plan 15-05's parser MUST bind to these bounds (not re-define them).

Full DoS mitigation (length cap, per-block entry cap enforcement) lands with the parse body in 15-05 — captured in that plan's threat model.

## Commits

| # | Hash | Type | Description |
|---|------|------|-------------|
| 1 | `b4670fd` | feat(15-02) | Base64Codec + CodecException + ParseCursor scaffolding |
| 2 | `4d50cb7` | feat(15-02) | Frame sealed hierarchy + supporting records + PerceptionCodec stub with DoS bound constants |
| 3 | `f35a98d` | test(15-02) | 13-vector round-trip test in RED state (Vector 9 = `v+F-3L5`) |

All commits used `--no-verify` per parallel-executor protocol.

## Handoff Notes for Plan 15-03 and Downstream

- Import surface is stable: `com.paralife.codec.Frame` (+ nested `RegisterFrame` / `SyncFrame` / `TickFrame` / `ActionFrame` / `ErrorFrame`), `com.paralife.codec.PerceptionCodec`, `com.paralife.codec.Coord`, `com.paralife.codec.KindData`, plus the six supporting records.
- DoS bound constants (`PerceptionCodec.MAX_S_ENTRIES`, `PerceptionCodec.MAX_V_ENTRIES`) are public — plan 15-05 MUST reference these constants (do not redefine).
- The round-trip test is the acceptance oracle for plan 15-05. When 15-05 completes, running `./gradlew test --tests 'com.paralife.codec.PerceptionCodecRoundTripTest'` must show all 13 vectors passing.
- `ParseCursor` is package-private — plan 15-05 will use it internally but callers outside `com.paralife.codec` never see it.
- `TickFrame.isMinimal()` is the API for downstream consumers (bots / handlers) to detect the §6.3.2 minimal form without reading `sensorRadius` directly.

## Self-Check: PASSED

- **Files created exist:**
  - `src/main/java/com/paralife/codec/Base64Codec.java` — FOUND
  - `src/main/java/com/paralife/codec/CodecException.java` — FOUND
  - `src/main/java/com/paralife/codec/ParseCursor.java` — FOUND
  - `src/main/java/com/paralife/codec/Frame.java` — FOUND
  - `src/main/java/com/paralife/codec/Coord.java` — FOUND
  - `src/main/java/com/paralife/codec/KindData.java` — FOUND
  - `src/main/java/com/paralife/codec/CellEntry.java` — FOUND
  - `src/main/java/com/paralife/codec/Event.java` — FOUND
  - `src/main/java/com/paralife/codec/ActiveEffect.java` — FOUND
  - `src/main/java/com/paralife/codec/StateChange.java` — FOUND
  - `src/main/java/com/paralife/codec/PoolSnapshot.java` — FOUND
  - `src/main/java/com/paralife/codec/RosterMember.java` — FOUND
  - `src/main/java/com/paralife/codec/PerceptionCodec.java` — FOUND
  - `src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java` — FOUND
- **Commits exist in git log:** `b4670fd`, `4d50cb7`, `f35a98d` — all FOUND
- **Compilation:** `./gradlew compileJava compileTestJava` → BUILD SUCCESSFUL
- **RED state verified:** Running the test yields 13 failures, all `UnsupportedOperationException`
- **Vector 9 literal:** `v+F-3L5` present exactly once; `v+0F-03L5` absent
- **No Spring / Jackson in `com.paralife.codec`:** confirmed via grep (0 matches)
