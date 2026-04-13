package com.paralife.bot;

import com.paralife.world.Entity.ParticleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Launches N bot clients with balanced particle types.
 * Each bot connects via WebSocket and runs autonomously using {@link HeuristicBrain}.
 */
public class BotLauncher {

    private static final Logger log = LoggerFactory.getLogger(BotLauncher.class);

    private final List<BotClient> bots = new CopyOnWriteArrayList<>();

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
        ParticleType[] types = ParticleType.values();
        List<BotClient> launched = new CopyOnWriteArrayList<>();
        CountDownLatch allDone = new CountDownLatch(count);
        AtomicInteger registered = new AtomicInteger(0);

        for (int i = 0; i < count; i++) {
            ParticleType type = types[i % types.length];
            BotClient bot = new BotClient(serverUri, type.name());
            launched.add(bot);
            bots.add(bot);

            Thread.startVirtualThread(() -> {
                try {
                    bot.connect();
                    if (bot.waitForRegistered(10, TimeUnit.SECONDS)) {
                        registered.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.warn("Bot failed to connect: {}", e.getMessage());
                } finally {
                    allDone.countDown();
                }
            });
        }

        // Wait for all bots to finish connecting + registering
        if (!allDone.await(30, TimeUnit.SECONDS)) {
            log.warn("Not all bots finished connecting within timeout");
        }

        log.info("BotLauncher: {}/{} bots registered", registered.get(), count);

        return new ArrayList<>(launched);
    }

    /**
     * Disconnect all launched bots.
     */
    public void shutdown() {
        for (BotClient bot : bots) {
            bot.disconnect();
        }
        log.info("BotLauncher: {} bots disconnected", bots.size());
        bots.clear();
    }

    /**
     * Get all launched bots.
     */
    public List<BotClient> getBots() {
        return List.copyOf(bots);
    }
}
