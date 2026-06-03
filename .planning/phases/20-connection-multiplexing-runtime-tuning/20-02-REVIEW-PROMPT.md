# Cross-AI Review Request — Paralife Phase 20 Plan 20-02

You are reviewing an **implementation plan** (`20-02-PLAN.md`) for a Spring Boot 3.4.4 /
Java 21 project. The plan adds a `@ConfigurationProperties` record (`JettyRuntimeConfig`)
that binds `paralife.runtime.jetty.*` and wires seven Jetty 12 WebSocket `Configurable`
setters through `JettyRequestUpgradeStrategy.addWebSocketConfigurer`, plus a legacy
idle-timeout fallback and tests.

**The review target is `20-02-PLAN.md` only.** The other files in the manifest are
**ground-truth references** — the actual codebase the plan will mutate. Read them with your
own file tools to verify the plan's claims. Do NOT review them for their own bugs.

## What makes a finding valuable here

This plan has already survived **three internal review passes** (the plan text cites
"Pass-2 Concern #16", "Pass-3 Concern #22", and "review concern #4"). Low-hanging fruit is
likely gone. Your value is in what those passes missed. **Verify, don't trust the plan's
self-description.** Specifically check the plan's concrete claims against the reference files:

- The plan says `JettyDeflateCustomizer.java` lines 75-82 hold the `@Bean` to mutate and
  lines 84-180 (the deflate enforcement filter) must stay untouched. **Open the file and
  confirm those line ranges and the current `@Bean` signature match.**
- The plan branches hard on "if Plan 1's A1 verification reported a Jetty `Configurable`
  setter unavailable on Jetty 12.0.18, drop that field/setter/yaml key." The plan's
  read_first cites `20-01-SUMMARY.md` for A1, but the plan's frontmatter `depends_on` is
  `20-01b`. **Read both `20-01-SUMMARY.md` and `20-01b-SUMMARY.md`: did A1 actually run?
  What did it find? Are all seven setters available, or should one be dropped? Is the plan
  pointing at the right summary?** A wrong/missing A1 source is a real risk.
- The plan claims its record mirrors the `AdmissionConfig` pattern and its test mirrors
  `AdmissionConfigTest.java:15`. **Open both and confirm the claimed precedent is real and
  the proposed shape actually matches** (`@ConstructorBinding`, `@DefaultValue`,
  `defaults()` factory, the `TestApp` `@EnableConfigurationProperties` wrapper).
- Scrutinise the `resolveEffectiveIdleMs` fallback logic (Task 2.2 / 2.3). The plan itself
  admits a limitation: with a primitive `@DefaultValue("60000")`, the record cannot tell
  "explicitly set to 60000" from "defaulted to 60000", so case A and case D both go through
  the new-key branch and case C uses `== 60000L` as a proxy for "new key unset." **Is the
  4-case truth table internally consistent? Are there operator-visible footguns this proxy
  creates that the plan hasn't pinned** (e.g. an operator who legitimately sets the NEW key
  to exactly 60000 while also setting the legacy key)?

## Also assess (standard plan-review lens)

- Missing edge cases / error handling; dependency-ordering issues; scope creep or
  over-engineering; security (frame-size DoS cap T-20-DOS-1); whether the plan's acceptance
  criteria (the `grep`/`awk` gates) actually prove what they claim; whether the plan
  achieves its stated "zero behavioural change at boot with no overrides" goal.

## Output format (Markdown)

1. **Summary** — one-paragraph assessment.
2. **Strengths** — bullets.
3. **Findings** — bullets. **Tag every finding with a severity** from this rubric and cite
   the file/line or plan section it concerns:
   - `BLOCKER` — must fix before executing the plan (would produce broken/incorrect code,
     data loss, or a security hole).
   - `HIGH` — serious bug or risk; likely to cause rework or a failed verification.
   - `MEDIUM` — real issue, non-urgent.
   - `LOW` — minor.
   - `NIT` — style/preference.
   State explicitly whether each finding is **verified against the reference files** or is a
   suspicion you could not confirm.
4. **Suggestions** — specific, actionable improvements.
5. **Risk Assessment** — overall execution risk (LOW/MEDIUM/HIGH) with one-line justification.

If you find nothing at or above MEDIUM after genuinely checking the references, say so
plainly — do not invent findings to fill the section.
