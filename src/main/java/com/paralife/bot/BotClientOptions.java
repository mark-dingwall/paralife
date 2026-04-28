package com.paralife.bot;

import java.util.Random;

/**
 * Immutable options record for {@link BotClient} construction (Phase 18 D-06 / RESEARCH.md Pitfall 2).
 *
 * <p>Introducing this record instead of adding a 7th positional arg avoids touching every
 * existing {@code BotClient} call site when harness identity is added. The legacy 3/5/6-arg
 * constructors delegate to {@link #defaults(String, char, HeuristicBrain)} and stay compilable.
 *
 * <p>Use {@link #defaults(String, char, HeuristicBrain)} for the common operator/test path
 * (defaults to {@link BotIdentity#unknown()}, 100ms cooldown, 50ms jitter, fresh RNG).
 */
public record BotClientOptions(
        String serverUri,
        char species,
        HeuristicBrain brain,
        long respawnCooldownMs,
        long respawnJitterMs,
        Random rng,
        BotIdentity identity) {

    /**
     * Convenience factory for the common path: reasonable defaults for respawn timing,
     * fresh RNG, and {@link BotIdentity#unknown()} as the source identity.
     *
     * <p>Existing constructors (3-arg, 5-arg, 6-arg) delegate to this path so all
     * pre-Phase-18 call sites continue to compile without modification (Pitfall 2 mitigation).
     */
    public static BotClientOptions defaults(String serverUri, char species, HeuristicBrain brain) {
        return new BotClientOptions(serverUri, species, brain, 100L, 50L, new Random(),
                BotIdentity.unknown());
    }
}
