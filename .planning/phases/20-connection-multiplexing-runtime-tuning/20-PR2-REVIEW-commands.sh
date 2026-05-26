#!/usr/bin/env bash
# First multi-review for PR #2 (feat/diagnostics-instrumentation).
# Flag-gated death-cause/lifespan instrumentation. Inline-only, all 4 reviewers,
# claude synthesizer. No --context (first review). No --timeout (run to completion).
#
# Run from the worktree root.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

PHASE_DIR=".planning/phases/20-connection-multiplexing-runtime-tuning"
PROMPT="$PHASE_DIR/20-PR2-REVIEW-PROMPT.md"
INLINE_OUT="$PHASE_DIR/20-PR2-REVIEW-inline.md"

REVIEW_FILES=(
  "$PHASE_DIR/20-PR2-REVIEW-DIFF.md"
  "src/main/java/com/paralife/diagnostics/DeathDiagnostics.java"
  "src/main/java/com/paralife/engine/SimulationEngine.java"
  "src/main/java/com/paralife/engine/EnvironmentEngine.java"
  "src/main/java/com/paralife/engine/DeathFinalizer.java"
  "src/main/java/com/paralife/engine/LiveEntityRegistry.java"
)

test -f "$PROMPT" || { echo "Missing prompt: $PROMPT" >&2; exit 1; }
for f in "${REVIEW_FILES[@]}"; do
  test -f "$f" || { echo "Missing input file: $f" >&2; exit 1; }
done

echo "==> INLINE mode -> $INLINE_OUT"
~/tools/multi-review/multi_review.py \
  --prompt-file "$PROMPT" \
  --mode inline \
  --reviewers claude,gemini,codex,opencode \
  --synthesizer claude \
  --output "$INLINE_OUT" \
  "${REVIEW_FILES[@]}"

echo "==> Done. inline: $INLINE_OUT"
