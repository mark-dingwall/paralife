# Environment mechanics

## Quantized toxin decay

**Why.** Toxin intensity is stored as an unsigned byte while decay is configured as a double. A
positive double can be too small to change `1.0` at IEEE-754 precision, so multiplication and
flooring alone can leave the grid maximum fixed forever.

**What changes / Impact.** `CellularAutomaton.diffuseStep` caps a positive-decay destination below
the source maximum. This affects toxin shadow-grid decay; no configuration defaults, wire frames,
or population tuning change. `CellularAutomatonTest` pins the smallest positive double-rate case.

**Assumptions / Open questions.** Rates remain validated in `[0, 1]`. Byte-intensity state cannot
represent a sub-unit decay, so one quantized level is the smallest observable positive effect.
This applies equally to all callers of the shared CA helper.

**Non-Goals.** This does not tune decay rates, alter diffusion neighbourhoods, add floating-point
state, or make any claim about long-run population outcomes; tuning remains outside the default
test suite.

**Readiness: GO.**

**EARS.** WHEN a nonzero intensity grid is diffused with a positive decay rate, THE SYSTEM SHALL
produce a destination maximum strictly lower than the source maximum. WHEN the rate is zero, THE
SYSTEM SHALL preserve a uniform source maximum absent diffusion loss.
