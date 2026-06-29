package com.paralife.codec;

import java.util.Optional;

/**
 * Single token in the `c` block per SCHEMA.md §8.2.
 * codes: C/M/S (bonded primary), D/N/T (bonded secondary), 0-5 (composite role), Z (dissolve).
 * ctx carries new maxEnergy (numeric base64) OR primary entityId (for secondary variants).
 */
public record StateChange(char code, Optional<String> ctx) {}
