# Cross-AI Review Request — Paralife Phase 20 Plan 20-02 (ROUND 2)

You are reviewing **round 2** of an implementation plan (`20-02-PLAN.md`) for a
Spring Boot 3.4.4 / Java 21 project. The plan adds a `@ConfigurationProperties`
record (`JettyRuntimeConfig`) that binds `paralife.runtime.jetty.*` and wires
all eight Jetty 12 WebSocket `Configurable` setters through
`JettyRequestUpgradeStrategy.addWebSocketConfigurer`, plus a legacy
idle-timeout fallback and tests.

**Round 1 already happened.** Four reviewers (claude/gemini/codex/opencode) ran
the same prompt; the consensus output is in `20-02-REVIEW-reference.md`.
The plan has since been edited to address most findings. Your job in **round 2**:

1. **Verify the fixes are correctly applied** (regression check — they may have
   introduced new bugs).
2. **Surface any NEW issues at-or-above HIGH severity** that round 1 missed.

You are not asked to re-flag any item from the "known — already addressed or
deliberately deferred" list below.

## What the review target is

**The review target is `20-02-PLAN.md` only.** The other files in the manifest
are **ground-truth references** — the actual codebase the plan will mutate, plus
the previous review and the dispositions doc. Read them with your own file
tools to verify the plan's claims. Do NOT review them for their own bugs.

## Round 1 fixes already applied — verify, do NOT re-flag unless regressed

| Round-1 finding | Fix applied | Verify by |
|---|---|---|
| **HIGH** Duplicate `paralife.runtime:` YAML key | Action rewritten to nest `jetty:` under the **existing** `runtime:` (sibling of `app:`); added duplicate-key guard `[ grep -c '^  runtime:' application.yml -eq 1 ]`; in-prose ⚠ warning added | Task 2.2 `<action>` yaml block; acceptance criteria for `runtime:` count |
| **MED** A1 cited from wrong summary (`20-01-SUMMARY.md` is the toolchain bootstrap) | All A1 references repointed `20-01-SUMMARY.md` → `20-01b-SUMMARY.md` (context include, Task 2.1 read_first, Task 2.2 read body, summary frontmatter dep already correct) | `read_first` lines in Tasks 2.1, 2.2 |
| **MED** 8th Jetty setter `setMaxOutgoingFrames` omitted | **Added.** Record now has 8 `@DefaultValue` fields (`maxOutgoingFrames` defaults to `-1` = Jetty's unlimited sentinel; carve-out: `-1` or `≥1`). Wiring lambda chains 8 setters. yaml carries `max-outgoing-frames: -1`. Reframed in javadoc: secondary to the Phase 17 D-10 `OutboundSender` queue | Task 2.1 record block; Task 2.2 wiring lambda; yaml block |
| **MED** Idle-timeout footgun: explicit new=60000 + legacy=45000 → 45000 silently wins, contradicting javadoc | Javadoc precedence corrected ("new wins **except** when new==default 60000"); 5th case **E** added to Task 2.3 truth table and a new nested test `CaseE_bothSetNewAtDefault` pins the documented (legacy-wins) behaviour | Task 2.2 `@Bean` javadoc; Task 2.3 truth table case E; Task 2.3 `CaseE_bothSetNewAtDefault` test |
| **LOW-MED** T-20-DOS-1 framing muddled (lower bound ≠ DoS cap) | Threat row + field javadoc reworded — DoS control is the **preserved 65536 default + launch-only** knob, not the lower-bound validation. The lower bound is now framed as a floor against operator misconfig, no longer "the cap" | `<threat_model>` T-20-DOS-1 row; record field javadoc on `maxFrameSize` |
| **LOW** "every validation path" overstated (only 4 of 6 fields tested) | Added `rejectsMaxBinaryTooSmall` + `rejectsMaxTextTooSmall` + `rejectsMaxOutgoingFramesZero` + `acceptsMaxOutgoingFramesUnlimitedAndPositive` (now 9 behavior tests total) | Task 2.1 `<behavior>` and test class body |
| **LOW** Acceptance grep false-positive (`idle-timeout-ms:` matches legacy key at line 42) | Replaced with `max-outgoing-frames: -1` token check (the token is unique to the new jetty block); also added the explicit `^  runtime:` count gate | Task 2.2 acceptance criteria |
| **LOW** Task 2.3 mis-described as "Spring slice test" — actually a pure helper unit test | Relabeled throughout: `<read_first>` note, `<behavior>` framing ("pure unit test of the static helper — no Spring context"), test-class javadoc | Task 2.3 `<read_first>`, `<behavior>`, javadoc |

## Known — deliberately deferred or out of scope, do NOT flag

- **NIT** `BindingRoundTripTest` discoverability under `./gradlew test --tests JettyRuntimeConfigTest`
  — relying on the same static-nested-`@SpringBootTest` shape as `AdmissionConfigTest.BindsAllKeys`,
  which is known to work. Confirmed cheap at execution time, not a plan-fix.
- **NIT** Test annotation tidy-up (`webEnvironment = NONE`, drop redundant
  `@ExtendWith(SpringExtension.class)`) — deliberate cosmetic deferral.
- **NIT** `grep -v '^#'` in one acceptance criterion — Java uses `//`/`*`, so the
  filter is a no-op; criterion still passes via the live `@Value`. Cosmetic only.
- **Full nullable / Spring `Binder` migration** for the idle-timeout explicit-set
  detection. Case E above is the documented + tested footgun; the full fix is
  out of scope for the MVP — deferred per concern #4 disposition.

## Round-2 lens — MVP is the goal

**Severity must be assigned under the MVP-is-the-goal lens.** This is a single
phase in a larger build. Flag what would break execution or quietly degrade the
delivered behaviour. Do NOT flag:

- Process / governance / "consider adding a CHANGELOG entry" / scope-creep
  suggestions.
- Stylistic preferences that don't change behaviour.
- Theoretical issues with no plausible operator path.
- Items already in the "known deferred" list above.

## What makes a finding valuable here

Round 1's low-hanging fruit is gone, and most of the obvious gaps have been
patched. Your value in round 2 is:

- **Regression detection:** did any of the round-1 fixes introduce a new bug?
  Especially: is the 8th-setter wiring internally consistent across record /
  yaml / lambda / acceptance grep? Is the round-1 javadoc-vs-behaviour
  reconciliation now actually consistent end-to-end (no two passages still
  disagree)?
- **Missed cross-cutting risks at HIGH+:** anything that would break execution,
  produce wrong code, regress observable behaviour, or wedge an acceptance gate
  in a way that passes when it shouldn't.

## Specifically verify (against the reference files)

- The plan claims `JettyDeflateCustomizer.java` lines 75-82 hold the `@Bean` to
  mutate and lines 84-180 (the deflate enforcement filter) must stay untouched.
  **Open the file and confirm those line ranges and the current `@Bean`
  signature match.**
- The plan claims A1 (`20-01b-SUMMARY.md` §A1) verified all 8 `Configurable`
  setters on Jetty 12.0.18. **Open `20-01b-SUMMARY.md` and confirm the 8-setter
  claim. Open `20-01-SUMMARY.md` and confirm it does NOT contain A1 data (it
  should be the async-profiler toolchain bootstrap).** A1 source mismatch is a
  HIGH bug if re-introduced.
- The plan claims its record mirrors `AdmissionConfig` and its binding test
  mirrors `AdmissionConfigTest.BindsAllKeys`. **Open both** and confirm the
  precedent is real and the proposed shape actually matches
  (`@ConstructorBinding`, `@DefaultValue`, `defaults()` factory, the `TestApp`
  `@EnableConfigurationProperties` wrapper).
- **`application.yml` has an existing `paralife.runtime:` block** holding
  `app:`. Confirm the plan's yaml action correctly nests the new `jetty:` as a
  sibling of `app:` under that existing key (NOT a second top-level
  `runtime:`). The duplicate-key guard `[ grep -c '^  runtime:' eq 1 ]` should
  be present in acceptance criteria.
- **Idle-timeout truth table** now has 5 cases (A–E) where case E pins the
  documented footgun (explicit new=60000 + legacy=45000 → 45000). Confirm the
  case-E test exists and asserts `45000L`. Confirm the `@Bean` javadoc says
  "new wins, **except** when new equals the default 60000".

## Output format (Markdown)

1. **Summary** — one-paragraph assessment, including whether the round-1 fixes
   appear correctly applied.
2. **Regression check** — per round-1 fix, one line each: applied-and-correct /
   applied-but-flawed / not-applied. (Use the table above as the checklist.)
3. **New findings** — bullets, **tag each with a severity** from this rubric and
   cite the file/line or plan section it concerns:
   - `BLOCKER` — must fix before executing the plan (would produce broken/incorrect
     code, data loss, or a security hole).
   - `HIGH` — serious bug or risk; likely to cause rework or a failed
     verification.
   - `MEDIUM` — real issue, non-urgent.
   - `LOW` — minor.
   - `NIT` — style/preference.
   State explicitly whether each finding is **verified against the reference
   files** or is a suspicion you could not confirm. Under the MVP lens, prefer
   under-flagging to over-flagging — if you can't justify HIGH+, mark it MEDIUM
   or lower.
4. **Risk Assessment** — overall execution risk (LOW/MEDIUM/HIGH) with
   one-line justification.

**If you find nothing at or above HIGH after genuinely checking the references,
say so plainly.** That is the success state of round 2. Do not invent findings
to fill the section.
