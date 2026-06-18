---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-03T18:13:00Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md"]
usage:
  claude: { input: 509654, output: 79, cached: 3528736, tool_calls: 10, elapsed_s: 242.5 }
  gemini: { input: 150628, output: 271, cached: 0, tool_calls: 0, elapsed_s: 110.3 }
  codex: { input: 683988, output: 9265, cached: 577536, tool_calls: 24, elapsed_s: 187.8 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 28, elapsed_s: 369.2 }
synthesizer: claude
synthesized_at: 2026-06-03T18:13:00Z
---

# Cross-AI Review

## Claude Review

Review done. Verified every load-bearing claim against live repo; doc is clean. Findings below — all LOW/NIT, nothing blocking.

## Verified-clean (cited doc claim → repo state)

- **15-SCHEMA.md §6/§8/§10 ref (caveman BUG fix)** — CORRECT. §6=Frame Grammars, §8=Block Grammars, §10=Round-trip Test Vectors (`15-SCHEMA.md:100,210,484`). Old broken `§12` was "Parser Implementation Notes". Fix landed right.
- **SCALE-08 quote (§1)** — verbatim vs `REQUIREMENTS.md:32`. (CLAUDE.md's "no REQUIREMENTS.md exists" guide is stale — file exists. Not this doc's problem.)
- **§2.1 Jetty knobs** — keys + defaults match `JettyRuntimeConfig` + `application.yml` (idle-timeout 60000, input-buffer 4096, max-outgoing-frames -1, legacy `paralife.websocket.idle-timeout-ms` back-compat present at yaml:42/64). All 8 launch-only confirmed.
- **§2.2 AppRuntimeConfig** — 4 fields all `[reserved]`, matches Plan 3.
- **§3 recipe keys** — all real + correctly nested: `paralife.admission.cap`, `paralife.simulation.spawn.seed` (yaml:104), active-variant `paralife.simulation.nutrient-spawn-probability` (yaml:95), `paralife.websocket.max-respawns-per-session` → `RespawnConfig.java:31` prefix `paralife.websocket`, field `maxRespawnsPerSession`. Copy-paste boots. F1 arithmetic checks (500−256=244, 1000−256=744 dropped).
- **JFR durations** match harness `--duration` per tier (60/90/180). Intro lands without contradicting per-tier blocks.
- **§4.1 helper docs** (`profiles/README.md`, `tools/async-profiler-bootstrap.md`) exist.
- **Cross-refs** (18-HARNESS §1 D-02 ceiling, D-21 exception; 17-ADMISSION §3; Phase 17 D-10) all accurate.

## Findings

`L131-132: NIT: "recording window covers exactly the load period" overstates — JFR starts at server boot (StartFlightRecording), ahead of harness connect + ramp, so early seconds capture idle/connect not load (worse at 60s tier). Fix: "...covers the load period (JFR begins at boot, a few seconds ahead of harness connect)."`

`L123-130: LOW: active-scenario variant inherits per-tier durations (60/90/180s), but the cited 103a615 active artifacts were captured at a uniform 90s window (20-01c §Active: "90 s profile window after 20 s ramp"). A Phase 21 script copying the active variant verbatim won't reproduce the cited JFR's window at the 100/1000 tiers. Fix: one line noting active captures use a uniform 90s window per 20-01c, OR that recipe durations are smoke-template only and Plan 5 sets the active capture window.`

`L325-330,337: NIT: baseline JFR + flamegraph rows still show "_≤10 MB_" bound although Plan 1c shipped them with known sizes (2.4/4.0/4.7 MB; flamegraphs 81/29/18 KB per 20-01c Inventory) — while active rows already carry real sizes, so precision is inconsistent. Fix: fill real sizes (or leave — Plan 6 finalises §6; backloggable).`

## Not flagged (confirmed already-disposed)

- **§3.1/§3.2 `parallelism=4/6` unsourced** → exactly the known-backlog item (VT-scheduler rationale para absent from §3.1/§3.2; Plan 6). Confirmed present, not re-flagged.
- **Heap presets (`-Xms/-Xmx`) carry no JFR cite and no `Pending` marker** → gemini pass-3 LOW, filed-as-followup (Plan 6 re-tunes heap). Resolved-by-disposition. Minor side-note: 20-04-SUMMARY's claim that heap is "cited to baseline JFR" is slightly overstated (recipe has no explicit heap↔JFR citation) — SUMMARY-accuracy nit, not a doc defect, Plan 6 owns it.
- §4 `Pending — JFR-driven` markers, footer `2026-06-XX`, 4 reserved fields, D-19 c22e487 reconciliation → all known backlog.

## Pushback / no creep

MVP discipline holds — §1/§2/§3/§5 populated, §4/§6 placeholders, nothing beyond Plan 20-04 scope. The polish-added active-variant + SIGTERM intro are §3 recipe content (in-scope), not creep. No unsourced recommendation slipped D-13: every recipe knob either cites the `62c1b44` JFR or is `Pending`-marked, except heap (disposed above) and §3.1/§3.2 parallelism (backlogged).

## Verdict

**PASS** for Plan 5 consumption.
1. Every code-grounded claim (cross-refs, config keys, defaults, SCALE-08 quote, recipe mechanics) verified accurate against live repo — recipes are copy-paste-runnable, which the human boot-verify already confirmed (rc=0, JFR present).
2. Three findings are LOW/NIT only; none misdirects Plan 5 or Phase 21, none loses data, none locks a wrong decision.
3. Cheap-to-fix-now: L131-132 wording + L123-130 active-window note (~2 lines); the §6 size precision is genuinely Plan 6 territory.

---

## Gemini Review

L126: NIT: Active-scenario variant instructs operators to append the `-Dparalife.simulation.nutrient-spawn-probability=0.05 \` flag "to the server JVM args block". Operators mechanically copy-pasting this to the very end of the bash snippet (after `-jar` and the app args) will inadvertently pass it as an ignored application argument or break the CLI. Consider either providing the Spring Boot app-arg equivalent (`--paralife.simulation.nutrient-spawn-probability=0.05`) to be safely appended at the end alongside the seed, or explicitly wording it as "insert before `-jar`".

(Note: The addition of `grep -v -- '-plain'` to the `SERVER_JAR` resolution at L148 is an excellent proactive touch that anticipates and neutralizes Spring Boot 3.4.x artifact glob ambiguity. Great attention to detail.)

PASS
The `20-RUNTIME.md` document is structurally sound, mechanically accurate, and rigorously adheres to all context decisions (D-01 through D-21, including all Pass-2 and Pass-3 amendment retags and conventions). It provides a pristine, copy-pasteable, and verifiable template that is fully ready for Plan 5 to inject its measured findings.

---

## Codex Review

L117: BLOCKER: The active-scenario variant says only append `-Dparalife.simulation.nutrient-spawn-probability=0.05` and keep the harness unchanged, but the active meta sidecars record `-Xms2g -Xmx2g`, `parallelism=8`, `--duration 130 --ramp-up rate:50`. Replace the generic variant with exact active-profile per-tier deltas from `profiles/jfr-*active-50xfood-103a615.meta.json`, or label it as smoke-only and not baseline-reproducible.

L131: HIGH: Equal JFR and harness durations do not “cover exactly the load period” because `StartFlightRecording` starts at JVM launch and the harness starts after server boot, so the recording can truncate the harness tail. Start JFR after boot with `jcmd JFR.start` immediately before the harness, or add startup/manual cushion and remove the “exactly” claim.

L151: MEDIUM: The lower-tier heap presets are untraceable recommendations: §3.1 uses `-Xms1g -Xmx1g` and §3.2 uses `-Xms1g -Xmx2g`, while the cited baseline and active meta sidecars were captured with `-Xms2g -Xmx2g`. Mark heap sizing `Pending - JFR-driven` or align lower tiers to the profiled `2g/2g` until Plan 5/6 supplies measured choices.

L74: LOW: `outbound-queue-size` is called “live-tunable”, but `AdmissionConfig` is immutable and `WorldWebSocketHandler.afterConnectionEstablished` passes the value into `OutboundSender.attachSession`, which creates a fixed `ArrayBlockingQueue`. Replace “live-tunable” with launch-time/new-session attach-time tunable unless a later refresh/admin path lands.

Overall: BLOCK for Plan 5 consumption.  
Reason: the active-profile recipe and JFR window text can produce non-comparable or truncated tuning evidence.  
After those are fixed, the remaining issues are D-13 honesty/polish and should be cheap to clean up.

---

## Opencode Review

Here's my review of `20-RUNTIME.md` (line numbers from the provided file, ~343 lines):

---

## Findings

**L79: NIT: §2 tuning surface table Row 3 says "mixed (live-tunable seams reserved for M5)".** All four `AppRuntimeConfig` fields are `[reserved — no effect in Phase 20]` per §2.2 immediately below. An operator skimming only the table could expect something is live-tunable today. Harm is near-zero because §2.2 is adjacent and explicit. Change "mixed" to "launch-only (live-tunable seams reserved for M5)" in the table row to match reality.

**L137: NIT: §3 intro "JFR duration / SIGTERM timing" says "covers exactly the load period."** JFR recording starts at `-jar` launch, not harness start — the window is (boot + load), not exactly load. The practical guidance (duration= matches harness `--duration`, SIGTERM after harness exits) is correct and safe. Drop "exactly" — "covers the load period" is sufficient.

**L87/L147/L246: LOW: `outbound-queue-size` described as "live-tunable" without qualification.** §2.2 calls it "the single most important live-tunable runtime knob in Phase 20", §5 echoes "The live-tunable backpressure knob", §3.2/§3.3 yaml comments present it with no lifecycle caveat. The actual consumer reads it at session creation (`OutboundSender.attachSession` time) — new sessions get the new depth, existing session queues keep their creation-time depth. A Phase 21 operator changing this mid-benchmark expecting existing-VT-queue resizing would be surprised. Fix: add "(affects new sessions only)" parenthetical at one of the three reference sites.

---

## Readiness: **FLAG (safe for Plan 5, but fix L87 before Plan 6 finalisation)**

Rationale: No BLOCKERs — all bindings, cross-refs, defaults, and recipes are mechanically correct. The three NIT/LOW items are cheap to fix. The LOW in particular could confuse a Phase 21 operator mid-benchmark; harmless to Plan 5 (codec opts) but worth adding the session-lifecycle qualifier before the doc becomes canonical for operators. The other two NITs are trivial word-tweaks.

---

## Consensus Summary

### Agreed Strengths
- Recipes are mechanically accurate and copy-paste runnable — config keys correctly nested, arithmetic checks out, boots clean (claude, gemini, opencode; claude notes human boot-verify rc=0 with JFR present).
- Config keys, defaults, and cross-references verified correct against the live repo (Jetty knobs, AppRuntimeConfig reserved fields, SCALE-08 quote, 18-HARNESS/17-ADMISSION refs) (claude, gemini, opencode).
- Structurally sound and ready for Plan 5 to inject measured findings; MVP discipline holds, no scope creep (claude, gemini, opencode).

### Agreed Concerns
- **JFR window "covers exactly the load period" is overstated** (severity NIT→HIGH; claude NIT, opencode NIT, codex HIGH). `StartFlightRecording` fires at JVM boot, ahead of harness connect/ramp — front of recording captures idle boot, and the harness tail can be truncated. Fix: drop "exactly", and either start JFR post-boot via `jcmd JFR.start` immediately before the harness or add a startup cushion.
- **Active-scenario variant won't reproduce the cited active JFR** (severity LOW→BLOCKER; claude LOW, codex BLOCKER). Variant inherits per-tier durations (60/90/180s) and omits the active meta sidecar params (`-Xms2g -Xmx2g`, `parallelism=8`, `--duration 130 --ramp-up rate:50`, uniform 90s window per 20-01c). A Phase 21 script copying it verbatim yields non-comparable tuning evidence. Fix: inject exact active per-tier deltas from `profiles/jfr-*active-50xfood-103a615.meta.json`, or label the variant smoke-only / not baseline-reproducible.
- **Heap presets untraceable to JFR** (severity LOW→MEDIUM; claude LOW/disposed, codex MEDIUM). §3.1 `-Xms1g -Xmx1g` and §3.2 `-Xms1g -Xmx2g` vs profiled `2g/2g`. Fix: mark `Pending — JFR-driven` or align lower tiers to `2g/2g` until Plan 5/6 supplies measured choices.
- **`outbound-queue-size` mislabeled "live-tunable"** (LOW; codex, opencode). Value is read once at `OutboundSender.attachSession`; existing session queues stay fixed at creation depth. Fix: qualify as attach-time / new-sessions-only.

### Divergent Views
- **Overall verdict split:** PASS (claude, gemini), FLAG-but-safe-for-Plan-5 (opencode), BLOCK (codex). Codex is the sole BLOCK, driven by the active-variant + JFR-window combination producing non-comparable or truncated tuning evidence. This is the crux to resolve: decide whether the active variant is a smoke-only template (then label it) or must reproduce baseline (then inject the deltas) — that one decision collapses the BLOCK.
- **Active-variant angle differs by reviewer:** claude/codex flag *parameter fidelity* (durations/heap/parallelism don't match the captured meta); gemini independently flags the same `-D…nutrient-spawn-probability` line as a *bash placement* hazard (appended after `-jar` becomes an ignored app arg) and suggests the `--property` app-arg form or "insert before `-jar`". Both worth fixing.
- **Single-reviewer items** (not corroborated, lower confidence): §2 table row "mixed / live-tunable seams" should read "launch-only" (opencode NIT); §6 baseline JFR/flamegraph rows still show "≤10 MB" though real sizes are known per 20-01c Inventory (claude NIT, likely Plan 6 territory).
