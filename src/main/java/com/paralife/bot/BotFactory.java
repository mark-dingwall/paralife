package com.paralife.bot;

import java.util.Optional;
import java.util.Random;

/**
 * Single chokepoint for {@link BotClient} construction (Phase 18 D-19).
 *
 * <p>The seam exists to support backlog 999.2 (bot-driven offspring): when 999.2 lands,
 * a server-driven offspring offer will trigger a fresh BotClient via this factory carrying
 * a claim token. The {@code claimEntityId} / {@code claimToken} parameters are
 * reserved-now-with-no-producer (D-19); today they are no-ops.
 *
 * <p>Both {@link BotRunner} (≤100-bot operator path) and {@code LoadHarness} (1000+ bot
 * harness path, Plan 05) use this factory, ensuring all bot construction goes through a
 * single choke-point.
 */
public final class BotFactory {

    private final String serverUri;

    public BotFactory(String serverUri) {
        this.serverUri = serverUri;
    }

    /**
     * Create a new {@link BotClient} ready to {@link BotClient#connect()}.
     *
     * @param species         species char — must be one of {@code C}, {@code M}, {@code S}
     * @param identity        harness identity; carried in {@code X-Paralife-Source} /
     *                        {@code X-Paralife-Harness} handshake headers on every connect()
     * @param claimEntityId   D-19 reserved for backlog 999.2 (bot-driven offspring); no-op today
     * @param claimToken      D-19 reserved for backlog 999.2 (bot-driven offspring); no-op today
     * @return a fresh {@link BotClient} with the given identity
     * @throws IllegalArgumentException if species is not C/M/S
     */
    public BotClient create(char species, BotIdentity identity,
                             Optional<String> claimEntityId,
                             Optional<String> claimToken) {
        // claimEntityId/claimToken are D-19 reserved params; ignored until 999.2 ships.
        BotClientOptions opts = new BotClientOptions(
                serverUri, species,
                new HeuristicBrain(HeuristicBrain.REPRODUCE_THRESHOLD),
                100L, 50L, new Random(), identity);
        return new BotClient(opts);
    }
}
