package com.paralife.engine.emergence;

import com.paralife.bot.BotClient;
import com.paralife.bot.HeuristicBrain;
import com.paralife.world.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic twin of {@code BotLauncher}: launches {@code count} bots
 * with per-bot RNGs derived from a single {@code masterSeed} via
 * {@link SplittableRandom#split()}. Zero unseeded-RNG usage — any
 * non-determinism defeats the seeding contract (threat T-16-14).
 *
 * <p>Latent dependency note: the seeded {@link Random} is honoured across
 * respawn jitter only after 16-01 Task 3's {@code BotClient.handleDeath:294}
 * fix lands. 16-04 (this plan) is Wave 1; consumers (16-05, 16-06) are
 * Wave 3+ so the fix is in place by the time this helper is exercised.
 */
public class SeededBotLauncher {

    private static final Logger log = LoggerFactory.getLogger(SeededBotLauncher.class);

    private final List<BotClient> bots = new CopyOnWriteArrayList<>();

    /**
     * Launch {@code count} bots against the given server URI. Each bot gets
     * its own {@link Random} derived from a distinct branch of
     * {@code SplittableRandom.split}, seeded from {@code masterSeed}.
     */
    public List<BotClient> launchSeeded(String serverUri, int count, long masterSeed) throws Exception {
        SplittableRandom master = new SplittableRandom(masterSeed);
        List<BotClient> launched = new CopyOnWriteArrayList<>();
        CountDownLatch allDone = new CountDownLatch(count);
        AtomicInteger registered = new AtomicInteger(0);
        Entity.ParticleType[] types = Entity.ParticleType.values();

        for (int i = 0; i < count; i++) {
            char species = switch (types[i % types.length]) {
                case CATALYST -> 'C';
                case MEMBRANE -> 'M';
                case SPORE -> 'S';
            };
            long botSeed = master.split().nextLong();
            BotClient bot = new BotClient(
                    serverUri,
                    species,
                    new HeuristicBrain(HeuristicBrain.REPRODUCE_THRESHOLD),
                    100L, 50L,
                    new Random(botSeed));
            launched.add(bot);
            bots.add(bot);

            Thread.startVirtualThread(() -> {
                try {
                    bot.connect();
                    if (bot.waitForRegistered(10, TimeUnit.SECONDS)) {
                        registered.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.warn("Seeded bot failed to connect: {}", e.getMessage());
                } finally {
                    allDone.countDown();
                }
            });
        }

        if (!allDone.await(30, TimeUnit.SECONDS)) {
            log.warn("Not all bots connected within 30s; registered={} of {}",
                    registered.get(), count);
        } else {
            log.info("SeededBotLauncher: {}/{} bots registered (seed={})",
                    registered.get(), count, masterSeed);
        }
        return new ArrayList<>(launched);
    }

    /** Disconnect all launched bots. */
    public void shutdown() {
        for (BotClient bot : bots) {
            try {
                bot.disconnect();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
        bots.clear();
    }

    public int botCount() { return bots.size(); }
}
