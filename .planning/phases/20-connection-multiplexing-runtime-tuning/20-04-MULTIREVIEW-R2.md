---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex"]
reviewers_failed: ["opencode"]
reviewed_at: 2026-06-03T11:36:49Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md"]
models:
  claude: "claude-opus-4-7"
  gemini: "gemini-3-pro-preview"
usage:
  claude: { input: 10, output: 16, cached: 0, tool_calls: 0, elapsed_s: 77.7 }
  gemini: { input: 162367, output: 246, cached: 0, tool_calls: 0, elapsed_s: 124.4 }
  codex: { input: 962968, output: 7931, cached: 867968, tool_calls: 18, elapsed_s: 172.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 1, elapsed_s: 93.1 }
synthesizer: claude
synthesized_at: 2026-06-03T11:36:49Z
---

# Cross-AI Review

## Claude Review

# Round-2 review findings — 20-04-PLAN.md

**HIGH — §3.3 respawn-cap workaround uses wrong config prefix.**
Plan suggests `-Dparalife.simulation.spawn.max-respawns-per-session=10`. Per `17-ADMISSION.md §8` migration table, key is `paralife.websocket.max-respawns-per-session` (stays at `RespawnConfig`, NOT moved under `paralife.simulation.spawn.*`). Phase 21 copy-pasting the recipe verbatim fails silently — `-D` with unknown key = ignored, cap stays at 5. Fix: change to `paralife.websocket.max-respawns-per-session=10` OR grep `RespawnConfig.java` for current `@ConfigurationProperties` prefix and use that.

**MEDIUM — broken cross-ref "20-01c §2.3".**
§3.3 respawn-cap caveat cites "20-01c §2.3 + Caveat #2". 20-01c-SUMMARY has no numbered §2.3 — section headers are unnumbered ("Per-Tier Headline", "Active-Population Workload", "Caveats"). Caveat #2 anchor is correct. Fix: drop "§2.3", keep "20-01c-SUMMARY Caveat #2 + Per-Tier Headline table" or similar.

**LOW — §3 recipes cite churn baseline; 20-01c says Plan 5 tunes against active profile.**
Each §3.X "Baseline JFR" line points at `jfr-Nbots-baseline-62c1b44.jfr` (churn). 20-01c-SUMMARY §Active-Population Workload: "Plans 20-04/05 should tune against the active profile, citing the churn baseline only for the env-CA fixed-cost floor." §3 intro acknowledges (active vs churn note + §6 indexes both), but per-recipe "Baseline JFR:" line ambiguously steers Phase 21 to the wrong file. Fix: add active-scenario citation alongside, e.g. "Baseline JFR (churn): `...-62c1b44.jfr`; Baseline JFR (active, **transport-tuning target per 20-01c**): `jfr-Nbots-active-50xfood-103a615.jfr`".

**NIT — §6 active sidecar size figure.**
Plan §6 table claims active metric sidecars `_~13–14 KB each_`. 20-01c-SUMMARY artifact inventory states "18 samples each" with no size figure for active metrics (baseline metrics are 13/13/14 KB at 6 samples). 18 samples ≈ 3× the size → likely 35–45 KB, not 13–14 KB. Self-consistent within plan but probably wrong vs disk. Verify or mark `_~size pending Plan 6_`.

Round-1 triage otherwise converged. Bash blocks parity, cap=1500 explainer, §6 SHA pattern (`*-active-50xfood-103a615.*`), `queue-watermark-pct` absence in override yaml all clean.

---

## Gemini Review

- **MEDIUM** — §3.3 `Respawn-cap caveat` cites `20-01c §2.3`, but `20-01c-SUMMARY.md` has no `§2.3` (it has `Caveat #2` and `§F1`). This is a broken cross-reference.
- **MEDIUM** — §6 Profile Index table omits the `meta.json` sidecars for both the baseline (`jfr-{100,500,1000}bots-baseline-62c1b44.meta.json`) and active (`jfr-{100,500,1000}bots-active-50xfood-103a615.meta.json`) captures that are present in the `20-01c-SUMMARY.md` Artifact Inventory.
- **LOW** — §6 Profile Index table omits placeholders for the tuned-state flamegraph HTML artifacts (`{cpu,alloc,lock}-1000bots-tuned-<HEAD>.html`), listing only the tuned JFR and tuned metrics JSON.

---

## Codex Review

**Findings**

HIGH — [20-04-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:326): `SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | head -1)` is still ambiguous. If Gradle’s normal `-plain.jar` exists, `ls | head -1` can select the non-executable plain jar before the Boot jar, so the recipe may not boot. Exclude `-plain` too or pin the exact Boot jar pattern.

MEDIUM — [20-04-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:419): respawn caveat cites `20-01c §2.3`, but `20-01c-SUMMARY.md` has no §2.3. The source is `20-01c-SUMMARY.md` Caveat #2, with supporting detail in F1 / Per-Tier Headline.

LOW — [20-04-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:242): active metric sidecar sizes are not `~13–14 KB each`; actual files are ~39–43 KB (`39566`, `42020`, `43024` bytes). Baseline metric sidecar sizing is correct.

**Checked Clean**

`cap=1500` explainer matches AdmissionGate world-aggregate behavior. Active profile rows use `*-active-50xfood-103a615.*` consistently. `LoadHarness.java:196` is the current startup log site. No actual YAML `queue-watermark-pct:` override is present in §3.2/§3.3.

---

## Opencode Review (FAILED)

**Status:** failed — empty output (<50 bytes)

Elapsed: 93.1s

---

## Consensus Summary

### Agreed Strengths
- Round-1 triage converged: `cap=1500` explainer, active SHA pattern `*-active-50xfood-103a615.*`, no spurious `queue-watermark-pct` override in YAML — all clean.
- Bash recipe parity and `LoadHarness.java:196` startup-log site verified.

### Agreed Concerns
- **MEDIUM — Broken cross-ref `20-01c §2.3`** (claude, gemini, codex). §3.3 respawn-cap caveat cites non-existent §2.3 in `20-01c-SUMMARY.md`. Correct source: Caveat #2 (+ F1 / Per-Tier Headline table). Fix: drop `§2.3`.
- **LOW/NIT — §6 active metric sidecar size wrong** (claude, codex). Plan claims `~13–14 KB each`; actual ~39–43 KB (18 samples ≈ 3× baseline). Verify or update.

### Divergent Views
- **HIGH — §3.3 respawn-cap config prefix** (claude only). Claude flags `-Dparalife.simulation.spawn.max-respawns-per-session=10` as wrong prefix per 17-ADMISSION migration table (should be `paralife.websocket.max-respawns-per-session`); silently ignored if copied verbatim into Phase 21. Gemini/codex did not surface. Worth grepping `RespawnConfig.java` `@ConfigurationProperties` prefix to confirm.
- **HIGH — §3 `SERVER_JAR` glob ambiguity** (codex only). `ls build/libs/paralife-*.jar | grep -v load-harness | head -1` may pick `-plain.jar` over Boot jar. Others did not flag. Fix: also exclude `-plain` or pin exact pattern.
- **LOW — §3 per-recipe "Baseline JFR" ambiguity churn vs active** (claude only). Per-recipe lines steer at churn baseline though 20-01c says tune against active profile; §3 intro acknowledges but per-recipe line ambiguous. Add active citation alongside.
- **MEDIUM — §6 omits `meta.json` sidecars** for baseline + active captures (gemini only). Present in 20-01c artifact inventory but not in Profile Index table.
- **LOW — §6 omits tuned flamegraph HTML placeholders** `{cpu,alloc,lock}-1000bots-tuned-<HEAD>.html` (gemini only); only tuned JFR + tuned metrics JSON listed.
