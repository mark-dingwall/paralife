# Phase 999.6: VT pinning — `synchronized(session)` → `ReentrantLock` conversion (BACKLOG STUB)

**Status:** Backlog (not promoted)
**Created:** 2026-05-09 (`/gsd-plan-phase 20 --reviews` — Concern #2 disposition; see `.planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEW-DISPOSITIONS.md`)
**Promote with:** `/gsd-capture --backlog promote` once a baseline JFR shows `jdk.VirtualThreadPinned` count exceeds the threshold defined in Phase 20 Plan 5 Task 5.0 decision tree (currently `>100/min @ 20ms threshold`)

## Why this is a backlog stub, not a Phase 20 plan

Cross-AI review of Phase 20 (gemini / claude / codex / opencode, see `.planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEWS.md` Consensus Concern #2) flagged the `synchronized(session)` → `ReentrantLock` conversion as the **single highest-impact code change Phase 20 could ship**. OpenCode argued it should be a first-class Plan 5 task. Per Phase 20 user-directive MVP-scope rule (verbatim 2026-05-09): the decision was to keep the existing scope-expansion checkpoint in Plan 5 and file this stub for future promotion. The Plan 5 decision tree (post-review-rewrite) explicitly invokes this phase 999.6 when pinning is the dominant signal.

This stub exists so a future review pass cannot re-flag the same concern as a fresh planning gap.

## Goal (when promoted)

Convert the four `synchronized(session)` writers documented in `CLAUDE.md` §Outbound concurrency to `ReentrantLock`-based mutual exclusion, eliminating virtual-thread carrier pinning at the WebSocket session-write boundary. Preserve the synchronized-session-monitor contract (every writer holds the per-session monitor for the actual `sendMessage` call) — the conversion is a like-for-like swap of monitor primitive, not a contract relaxation.

## Affected sites (per CLAUDE.md §Outbound concurrency)

1. `OutboundSender.drainLoop` — drain VT path; the `synchronized(session)` block wrapping `session.sendMessage(...)`.
2. `WebSocketKeepaliveService.onTick` — keepalive PING writer; same monitor.
3. `WorldWebSocketHandler.sendOutOfBand` — out-of-band stall/error frames; same monitor.
4. `WorldWebSocketHandler.sendFrame` — back-compat fallback; same monitor.

All four sites share a common contract: encoding stays OUTSIDE the monitor; only the non-thread-safe `session.sendMessage` invocation is inside it. The conversion replaces `synchronized(session) { session.sendMessage(...); }` with `lock(session).lock(); try { session.sendMessage(...); } finally { lock(session).unlock(); }` (or equivalent `Lock` usage), where `lock(session)` returns a per-session `ReentrantLock` from a `WeakHashMap<WebSocketSession, ReentrantLock>` (or attached as a session attribute to mirror the existing `ATTR_*` pattern).

## Promotion gate

Promote when ANY of:

1. **Phase 20 Plan 5 Task 5.0 reports `pinning-dominates`** (reply signal; per 20-05-PLAN.md decision tree). The Plan 5 executor will already have captured the JFR evidence; the promotion uses that JFR as the input data.
2. **Phase 21 benchmark gate (SCALE-10)** finds `jdk.VirtualThreadPinned` correlates with tick-drift regressions at 1000+ bots, proving pinning is the binding constraint on M4's scale envelope.
3. **A future operator running the Phase 20 per-tier recipes captures a JFR showing pinning > the threshold**, and reports it via the issue tracker. The committed baseline JFR (`profiles/jfr-1000bots-baseline-c22e487.jfr`, anchored to Phase 19.1 close per D-19) does not by itself promote this phase — only post-promotion fresh evidence does.

## Out of scope for this stub

- Locking strategy choices beyond `ReentrantLock` (e.g., `StampedLock`, lock-free `MpscQueue` for outbound) — researched at promote-time, not now.
- Rewriting the synchronized-session-monitor contract documentation in `CLAUDE.md` §Outbound concurrency — that ships with the conversion, not in advance.
- Backporting the conversion to earlier phases.

## References

- `CLAUDE.md` §Outbound concurrency — current synchronized-session-monitor contract
- `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RESEARCH.md` §Pitfall 2 — the original pinning-risk write-up
- `.planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEWS.md` Consensus Concern #2 — cross-AI review framing
- `.planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEW-DISPOSITIONS.md` Concern #2 — disposition rationale
- `.planning/phases/20-connection-multiplexing-runtime-tuning/20-05-PLAN.md` Task 5.0 — decision tree's `pinning-dominates` outcome that triggers promotion
- `.planning/phases/19.1-address-p19-multi-review-pass-4-findings-f1-f2-f3-unshipped-/19.1-03-PLAN.md` — markStalled close-aware detach + L1 detach-timeout counter (the related concurrency hardening this conversion would build on)

---

*Phase 999.6 — backlog stub · created 2026-05-09 by `/gsd-plan-phase 20 --reviews`*
