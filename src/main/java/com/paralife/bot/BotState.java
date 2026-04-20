package com.paralife.bot;

/**
 * Bot's local projection of its own identity. Three orthogonal fields — {@link #species}
 * is invariant across the bot's lifetime (chosen at first {@code r|}); {@link #embodiment}
 * and {@link #compositeRole} update via {@link #withChangeCode(char)} in response to
 * SCHEMA §8.2 {@code c} block transitions.
 *
 * <p>Split introduced in Phase 15 per cross-AI review (Codex #a) — the previous
 * single {@code currentType} char conflated species (C/M/S), bonded orientation
 * (D/N/T), and composite role (0-5). Splitting makes {@code HeuristicBrain} easier
 * to reason about: species determines prey/predator; embodiment determines action
 * tier; compositeRole determines passive/authority-lite/authority.
 */
public record BotState(char species, Embodiment embodiment, Integer compositeRole) {

    public enum Embodiment {
        /** Solo Particle — full action tier (M/E/A/R). */
        SOLO,
        /** Bonded pair, this bot is the primary — full action tier. */
        BONDED_PRIMARY,
        /** Bonded pair, this bot is the secondary — passive (primary decides). */
        BONDED_SECONDARY,
        /** Member of a composite — action tier depends on {@code compositeRole} (§7). */
        COMPOSITE_MEMBER
    }

    public BotState {
        if (species != 'C' && species != 'M' && species != 'S') {
            throw new IllegalArgumentException("species must be C/M/S: " + species);
        }
        if (embodiment == null) {
            throw new IllegalArgumentException("embodiment must not be null");
        }
        if (embodiment == Embodiment.COMPOSITE_MEMBER) {
            if (compositeRole == null || compositeRole < 0 || compositeRole > 5) {
                throw new IllegalArgumentException(
                        "COMPOSITE_MEMBER requires compositeRole in 0..5, got: " + compositeRole);
            }
        } else {
            if (compositeRole != null) {
                throw new IllegalArgumentException(
                        "Non-COMPOSITE_MEMBER embodiment must have null compositeRole, got: " + compositeRole);
            }
        }
    }

    /** Initial state after first {@code r|<species>} registration. */
    public static BotState initial(char species) {
        return new BotState(species, Embodiment.SOLO, null);
    }

    /**
     * Apply a SCHEMA §8.2 {@code c} block transition code.
     *
     * <table>
     *   <caption>State-change code mapping</caption>
     *   <tr><th>Code</th><th>Transition</th></tr>
     *   <tr><td>C / M / S</td><td>BONDED_PRIMARY (species unchanged)</td></tr>
     *   <tr><td>D / N / T</td><td>BONDED_SECONDARY</td></tr>
     *   <tr><td>0 - 5</td><td>COMPOSITE_MEMBER with that role</td></tr>
     *   <tr><td>Z</td><td>Dissolved → SOLO</td></tr>
     * </table>
     */
    public BotState withChangeCode(char code) {
        return switch (code) {
            case 'C', 'M', 'S' -> new BotState(species, Embodiment.BONDED_PRIMARY, null);
            case 'D', 'N', 'T' -> new BotState(species, Embodiment.BONDED_SECONDARY, null);
            case '0', '1', '2', '3', '4', '5' ->
                    new BotState(species, Embodiment.COMPOSITE_MEMBER, code - '0');
            case 'Z' -> new BotState(species, Embodiment.SOLO, null);
            default -> throw new IllegalArgumentException("Unknown c-block code: " + code);
        };
    }

    /**
     * True when this bot has full action authority (M/E/A/R): solo, bonded primary,
     * or composite LOCOMOTOR (role 0).
     */
    public boolean hasFullAuthority() {
        return embodiment == Embodiment.SOLO
                || embodiment == Embodiment.BONDED_PRIMARY
                || (embodiment == Embodiment.COMPOSITE_MEMBER && compositeRole != null && compositeRole == 0);
    }
}
