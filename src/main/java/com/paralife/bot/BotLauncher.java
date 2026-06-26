package com.paralife.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Thin deprecated facade over {@link BotFleet} (Phase 18 D-04).
 *
 * <p><b>Why retained rather than deleted:</b> {@code BotClientIntegrationTest}
 * still imports and uses {@code BotLauncher} directly. Deleting it would break
 * that test. (Other load/emergence tests use {@code BotFleet} or
 * {@code SeededBotLauncher}, not this facade.)
 * The facade preserves the original observable contract (launch + waitForRegistered timeout,
 * shutdown, getBots) while delegating to {@link BotFleet} internally.
 *
 * <p><b>Migration path:</b> callers should migrate to {@link BotFleet} directly.
 * The old 30s ceiling ({@code allDone.await(30, TimeUnit.SECONDS)}) is preserved
 * for backwards compatibility — {@code BotFleet.awaitAllSettled().get(30, ...)} replicates
 * the same behaviour.
 *
 * @deprecated Use {@link BotFleet} directly. Removal is tracked under backlog item
 *     {@code BACKLOG-18-L4} (filed via {@code /gsd-add-backlog} as a follow-up to the
 *     Phase 18 Chunk B Round B remediation); this facade will be removed in a future phase.
 */
@Deprecated(since = "0.18", forRemoval = true)
public class BotLauncher {

    private static final Logger log = LoggerFactory.getLogger(BotLauncher.class);

    private final BotFleet fleet = new BotFleet();
    private volatile String serverUri;

    /**
     * Launch N bots connecting to the given server URI.
     * Bot types are distributed evenly across CATALYST/MEMBRANE/SPORE.
     * Connections are made concurrently using virtual threads.
     *
     * @param serverUri WebSocket server URI (e.g. "ws://localhost:8080/ws/world")
     * @param count     number of bots to launch
     * @return list of BotClient instances (some may not be registered)
     */
    public List<BotClient> launch(String serverUri, int count) throws Exception {
        this.serverUri = serverUri;
        BotFactory factory = new BotFactory(serverUri);
        List<BotClient> bots = fleet.launch(
                serverUri, count,
                BotIdentity.unknown(),   // back-compat: old BotLauncher had no identity
                RampUpSpec.instant(),
                SpeciesMix.balanced(),
                factory);

        // Preserve the old 30s ceiling for back-compat with existing callers.
        try {
            fleet.awaitAllSettled().get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Not all bots finished connecting within timeout");
        } catch (Exception e) {
            log.warn("Error awaiting bots: {}", e.getMessage());
        }

        log.info("BotLauncher: {}/{} bots registered", fleet.currentRegistered(), count);
        return bots;
    }

    /**
     * Disconnect all launched bots.
     */
    public void shutdown() {
        fleet.shutdown();
        log.info("BotLauncher: shutdown complete");
    }

    /**
     * Get all launched bots.
     */
    public List<BotClient> getBots() {
        return fleet.getBots();
    }
}
