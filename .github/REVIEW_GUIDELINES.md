# Paralife — Code Review Guidelines

> **Audience:** any agent or human reviewing a Paralife pull request, and any author
> responding to a review. Applies to automated PR-review bots (Claude / Codex / Gemini)
> **and** to local pre-PR reviews. The status check a bot posts is the merge gate; this
> file defines how to reach a pass/fail and how to report it.

## 0. Read project context first

Before reviewing, read the project's architecture, conventions, and **testing philosophy**
in `CLAUDE.md` (§Architecture, §Conventions, §Testing philosophy) and — where the diff
touches the wire — `15-SCHEMA.md`. A review that ignores Paralife's invariants is noise.
The highest-value, most-violated invariants:

- **Single-threaded simulation core** — all world mutations happen inside ordered
  `@EventListener(TickEvent)` handlers; nothing else mutates the grid.
- **WS:entity 1:1** — one WebSocket connection per entity; multi-entity-per-session needs an ADR.
- **Wire-schema bit-exactness** — `entityStatus` / `cellStatus` bit layouts are a contract
  (`15-SCHEMA.md` §8.1.2 / §8.1.3); codec round-trips must hold.
- **`synchronized(session)` monitor contract** — every `sendMessage` writer holds the session
  monitor for the send; encoding and metric recording stay *outside* the monitor.
- **Lock ordering** — grid write → index; never the inverse (deadlock risk).
- **Testing charter** — pin mechanics, defer emergence/load; assert against *independent*
  constants (never the code under test); every negative assertion needs a positive control.

## 1. Relationship to the Superpowers skills (no clash)

These guidelines **specialize**, and do not replace, the Superpowers `requesting-code-review`
and `receiving-code-review` skills (pinned SHA in `CLAUDE.md` §On-Demand Skills). Division of
labour:

- **The skills own the *mechanics*** — dispatching a reviewer with fresh, crafted context;
  read-only review; the adversarial lens; reception discipline. Keep using them locally.
- **This file owns the *Paralife gate*** — the severity→pass/fail mapping, the PR comment
  format, and the project-specific review dimensions.
- **Severity bridge:** the skills use a 3-tier scale (Critical / Important / Minor). Here, the
  skills' **Important splits into High (blocks) and Medium (backlog)** — see §3. When running a
  local skill-based review, map your findings onto this 4-tier scale before issuing a verdict.

## 2. Posture: adversarial

Review to **refute**, not to confirm. Assume a defect exists and hunt its failure mode — the
race, the unhandled edge, the broken invariant, the test that passes only by luck. Acknowledge
genuine strengths first (accurate praise makes the rest of the review trusted), then attack.
For changes on the hot path (`websocket` / `admission` / tick pipeline / `codec`), run an
explicit **try-to-refute pass**: state precisely how you would break it, then check whether the
diff is actually safe against that.

## 3. Severity & the gate

**The status check is binary. FAIL iff the diff introduces ≥1 Critical or High finding.**
Everything Medium-and-below merges (Medium tracked, Low noted). **Pre-existing issues never fail
a PR** — flag them as notes at most. **Review the diff only**, not the repo's accumulated debt.

| Tier | Check | Examples (introduced by *this* diff) | Comment section |
|------|-------|--------------------------------------|-----------------|
| **Critical** | ❌ FAIL | Secret/credential committed · data corruption · build or default-suite test red · prod-breaking change · security hole (injection, unsafe deserialization) | Blockers |
| **High** | ❌ FAIL | Correctness bug in changed logic · new behaviour shipped with no test · testing-charter violation in a new/changed test (couples to tunable defaults, emergence in the fast gate, negative assertion w/o positive control, self-referential expected value) · concurrency or hot-path hazard · wire-schema break · WS:entity 1:1 without ADR · reintroduced leak or tight async deadline | Blockers |
| **Medium** | ✅ PASS + backlog | Non-hot-path perf regression · observability/metric gap · missing non-load-bearing edge-case test · cold-path error handling · doc drift · untracked TODO | Backlog → `TD-*` |
| **Low / nit** | ✅ PASS + note | Naming, comments, formatting, optional simplification | Notes |

**Calibration:** *not everything is Critical* — never inflate a nit. Default to PASS unless there
is a *real* Critical/High introduced by *this* diff. The `ci.yml` test job is a **separate hard
correctness gate**, so you need not re-police what the tests already cover — your job is to block
*unsafe* merges, not to enforce zero-debt.

## 4. Review dimensions

- **Alignment** — does the change do what the PR says? Are deviations justified, or problematic?
- **Correctness** — logic, edge cases, error handling on real paths.
- **Concurrency & hot-path safety** — single-threaded sim-core rule, the `synchronized(session)`
  monitor contract, grid→index lock order, no leaked / "did-not-exit" virtual threads, no new
  tight async deadline that will flake under `forkEvery=0`.
- **Wire-schema** — `15-SCHEMA.md` bit layout and codec round-trips held exactly.
- **Testing charter** — mechanics pinned to independent constants; emergence/load kept out of the
  fast gate (`@Tag("slow")`); every negative assertion paired with a positive control.
- **Production readiness** — config/migration impact, backward compatibility, docs.
- **Security** — secrets, input handling, deserialization.

## 5. The PR comment (one per reviewer, updated in place)

Post exactly one comment per reviewing identity, located and **updated** on each re-review via
its hidden marker — never spam a fresh comment per push. Render only non-empty sections; a clean
PASS still comments ("No blocking findings").

```markdown
<!-- paralife-review:<reviewer-id> -->
## 🤖 <Reviewer> review — VERDICT: ❌ FAIL · Ready to merge: No
**Scope:** <base>…<head> · **Re-reviews:** <n> · **Adversarial pass:** <ran / hot-path try-to-refute>

### ✅ Strengths
- <specific, genuine — builds trust in the rest>

### ❌ Blockers — Critical/High, must fix before merge
- `path:line` — <what's wrong> · why: <impact> · fix: <how>

### 📋 Backlog — Medium, non-blocking, track as tech-debt
- <finding> → propose row for `.planning/STATE.md` §Deferred Items:
  `| tech-debt | TD-XX-… — <one-liner> | open | <date> |`

### 💬 Notes / nits — Low, no action required
- `path:line` — <minor>

_Verdict basis: <one line>._
```

- **Medium → propose a `TD-*` row** in the comment; do **not** auto-edit `.planning/STATE.md`
  (agents racing on that file is worse than the debt). A human or the author's follow-up commit
  applies accepted rows.
- **Re-review on every push:** re-evaluate, update the same comment, flip the check; drop findings
  that were fixed.

## 6. Reviewer discipline

- **Read-only.** Never mutate the working tree / index / HEAD / branch. Inspect via
  `git show` / `git diff` / `git log`; check out other revisions into a throwaway worktree.
- **Fresh context.** Review the work product (the diff + the PR's stated intent + project
  context), not the author's chat history — this is what stops rubber-stamping.
- **Be specific.** Every finding: `file:line` · what's wrong · why it matters · how to fix.
  Never say "looks good" without checking; never review code you did not read; always give a
  clear verdict (Yes / No / With-fixes).

## 7. Author-side: receiving a review (clearing a FAIL)

From `receiving-code-review` — technical rigor, not performative agreement:

- **No performative agreement.** Not "you're absolutely right", not "great catch", no thanks.
  State the fix, or push back.
- **Verify before implementing.** Check each finding against the codebase. If you cannot verify,
  say so and ask for direction.
- **Push back with reasoning** when a finding breaks something, lacks context, is YAGNI (grep for
  usage first), is wrong for this stack, or conflicts with a prior architectural decision.
  Severity miscalibration is itself valid pushback ("this is Medium, not High, because …").
- **Backlog deliberately.** A Critical/High must be fixed (or correctly re-graded). A Medium may
  be deferred *only* with a justification and a tracked `TD-*` row.
- **Reply in the comment thread**, not as a top-level PR comment. Fix one item at a time.
- A FAIL flips to PASS when every Critical/High is fixed-or-correctly-regraded and every accepted
  Medium has a tracked `TD-*`.

## 8. Reviewer prompt (drop-in)

A review bot can load this verbatim:

```
You are an adversarial code reviewer for the Paralife project. First read CLAUDE.md
(Architecture, Conventions, Testing philosophy) and .github/REVIEW_GUIDELINES.md. Then
review ONLY the diff <base>..<head>. You are READ-ONLY: never mutate the tree, index, or HEAD.

Try to REFUTE the change: find the race, the broken invariant, the unhandled edge, the test
that only passes by luck. For hot-path diffs (websocket/admission/tick/codec) state how you
would break it, then verify whether the diff is safe.

Grade each finding Critical / High / Medium / Low per the guidelines. Post ONE status check —
FAIL iff the diff introduces >=1 Critical or High — and ONE PR comment using the template in
§5 (Strengths first; Blockers; Backlog→TD-*; Notes). Be specific: file:line, what, why, how.
Pre-existing debt never fails a PR. Default to PASS unless there is a real Critical/High.
```
