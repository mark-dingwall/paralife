#!/usr/bin/env bash
# Independent multi-agent review of Plan 20-01b baseline capture.
# Two runs (reference + inline), all 4 agents (claude / gemini / codex / opencode),
# claude default synthesizer (note: double-weighted as reviewer+synthesizer per user request).
#
# Run from the project root: /home/mark/kramtime/paralife
#
# Wall time: ~5–15 min per run depending on reviewer latency. Two runs ~10–30 min.
# Cost: see EXPERIMENTS.md after; gemini fallback chain may fire on capacity 429s.

set -euo pipefail

MR=~/tools/multi-review/multi_review.py
PHASE_DIR=.planning/phases/20-connection-multiplexing-runtime-tuning
PROMPT_FILE="$PHASE_DIR/20-01b-REVIEW-PROMPT.md"

# All 14 files the prompt references. Order is meaningful for inline mode
# (planning docs first, sampled data next, source code last — model attention
# is strongest at the head and tail of long contexts).
FILES=(
  "$PHASE_DIR/20-01b-PLAN.md"
  "$PHASE_DIR/20-01b-SUMMARY.md"
  "$PHASE_DIR/profiles/metrics-100bots-baseline-c22e487.json"
  "$PHASE_DIR/profiles/metrics-500bots-baseline-c22e487.json"
  "$PHASE_DIR/profiles/metrics-1000bots-baseline-c22e487.json"
  "$PHASE_DIR/profiles/jfr-1000bots-baseline-c22e487.meta.json"
  src/main/java/com/paralife/admission/AdmissionMetrics.java
  src/main/java/com/paralife/admission/TickHealthMonitor.java
  src/main/java/com/paralife/admission/AdmissionGate.java
  src/main/java/com/paralife/admission/OutboundSender.java
  src/main/java/com/paralife/engine/TickEngine.java
  src/main/java/com/paralife/websocket/TickBroadcaster.java
  src/main/java/com/paralife/harness/LoadHarness.java
  src/main/resources/application.yml
)

# Sanity check before launching
test -f "$PROMPT_FILE" || { echo "Missing prompt: $PROMPT_FILE" >&2; exit 1; }
for f in "${FILES[@]}"; do
  test -f "$f" || { echo "Missing input file: $f" >&2; exit 1; }
done

run_reference() {
  echo "=== Run 1/2: reference mode (manifest of paths, reviewers tool-read each file) ==="
  "$MR" \
    --mode reference \
    --prompt-file "$PROMPT_FILE" \
    --output "$PHASE_DIR/20-01b-REVIEW-reference.md" \
    --reviewers claude,gemini,codex,opencode \
    --synthesizer claude \
    --project-tag paralife-20-01b \
    "${FILES[@]}"
}

run_inline() {
  echo "=== Run 2/2: inline mode (file contents embedded in prompt) ==="
  "$MR" \
    --mode inline \
    --prompt-file "$PROMPT_FILE" \
    --output "$PHASE_DIR/20-01b-REVIEW-inline.md" \
    --reviewers claude,gemini,codex,opencode \
    --synthesizer claude \
    --project-tag paralife-20-01b \
    "${FILES[@]}"
}

case "${1:-both}" in
  reference) run_reference ;;
  inline)    run_inline ;;
  both)
    run_reference
    echo
    run_inline
    echo
    echo "Both runs complete. Compare:"
    echo "  $PHASE_DIR/20-01b-REVIEW-reference.md"
    echo "  $PHASE_DIR/20-01b-REVIEW-inline.md"
    ;;
  *)
    echo "Usage: $0 [reference|inline|both]" >&2
    exit 2 ;;
esac
