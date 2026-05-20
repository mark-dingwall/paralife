#!/usr/bin/env bash
# Pass-2 multi-review for Phase 20 Plan 01c PR #1.
# Runs all four reviewers (claude / gemini / codex / opencode) in both inline
# and reference modes against the remediation set (D1/D2/D3 code + SUMMARY +
# representative sidecar). Pass-1 review artifacts + remediation plan are
# supplied as --context so reviewers can defer to prior dispositions.
#
# Expected wall: ~5–10 min per mode. Run from worktree root.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

PHASE_DIR=".planning/phases/20-connection-multiplexing-runtime-tuning"
PROFILES_DIR="$PHASE_DIR/profiles"

PROMPT="$PHASE_DIR/20-01c-REVIEW-pass2-PROMPT.md"

REVIEW_FILES=(
  "src/main/java/com/paralife/websocket/WorldWebSocketHandler.java"
  "src/main/java/com/paralife/admission/OutboundSender.java"
  "src/main/java/com/paralife/admission/AdmissionMetrics.java"
  "$PHASE_DIR/20-01c-SUMMARY.md"
  "$PROFILES_DIR/metrics-1000bots-baseline-0824f1a.json"
  "$PROFILES_DIR/metrics-500bots-baseline-0824f1a.json"
  "$PROFILES_DIR/metrics-100bots-baseline-0824f1a.json"
  "$PROFILES_DIR/jfr-1000bots-baseline-0824f1a.meta.json"
)

CONTEXT_FILES=(
  --context "$PHASE_DIR/20-01c-REVIEW-inline.md"
  --context "$PHASE_DIR/20-01c-REVIEW-reference.md"
  --context "/home/mark/.claude/plans/vast-inventing-simon.md"
)

INLINE_OUT="$PHASE_DIR/20-01c-REVIEW-pass2-inline.md"
REFERENCE_OUT="$PHASE_DIR/20-01c-REVIEW-pass2-reference.md"

echo "==> INLINE mode -> $INLINE_OUT"
~/tools/multi-review/multi_review.py \
  --prompt-file "$PROMPT" \
  --mode inline \
  --reviewers claude,gemini,codex,opencode \
  --synthesizer claude \
  "${CONTEXT_FILES[@]}" \
  --output "$INLINE_OUT" \
  "${REVIEW_FILES[@]}"

echo "==> REFERENCE mode -> $REFERENCE_OUT"
~/tools/multi-review/multi_review.py \
  --prompt-file "$PROMPT" \
  --mode reference \
  --reviewers claude,gemini,codex,opencode \
  --synthesizer claude \
  "${CONTEXT_FILES[@]}" \
  --output "$REFERENCE_OUT" \
  "${REVIEW_FILES[@]}"

echo "==> Done."
echo "  inline:    $INLINE_OUT"
echo "  reference: $REFERENCE_OUT"
