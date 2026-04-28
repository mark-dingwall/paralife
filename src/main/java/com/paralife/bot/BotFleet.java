package com.paralife.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async VT-per-bot fleet launcher shared by {@link BotRunner} (≤100-bot operator path)
 * and {@code LoadHarness} (1000+ bot harness path). Replaces the 30s-bounded
 * {@link BotLauncher#launch} ceiling (Phase 18 D-04 / RESEARCH.md Pitfall 3).
 *
 * <p><b>No 30s ceiling:</b> {@link #launch} returns immediately after firing one virtual
 * thread per bot. Callers choose their own observability via {@link #awaitAllSettled()},
 * {@link #peakRegistered()}, or {@link #currentRegistered()}.
 *
 * <p><b>Peak tracking:</b> {@code peakRegistered} is a true high-water mark — it equals
 * {@code max(currentRegistered)} over the JVM lifetime and never decreases.
 *
 * <p><b>Idempotent shutdown (Round 2 Claude MEDIUM):</b> {@link #shutdown()} is guarded by
 * {@link AtomicBoolean#compareAndSet} so double-call from a shutdown hook and the main
 * {@code run()} path is safe — {@link BotClient#disconnect()} is invoked exactly once per bot.
 */
public final class BotFleet {

    private static final Logger log = LoggerFactory.getLogger(BotFleet.class);

    /**
     * Per-bot registration result, resolved by the launch virtual thread once
     * {@link BotClient#awaitRegistered} completes or fails.
     */
    public record RegistrationResult(String botId, boolean registered, Optional<String> failureReason) {}

    private final CopyOnWriteArrayList<BotClient> bots = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<RegistrationResult>> futures =
            new ConcurrentHashMap<>();

    /** Live count of registered bots. Incremented on successful registration; decremented via onClose. */
    private final AtomicInteger liveCount = new AtomicInteger(0);

    /**
     * High-water mark: incremented alongside {@code liveCount} on registration; NEVER decremented.
     * Represents the maximum concurrent registered count observed since this fleet was created.
     */
    private final AtomicInteger highWater = new AtomicInteger(0);

    /**
     * Round 2 Claude MEDIUM: idempotent shutdown guard.
     * Guards against double-call from shutdown hook + main {@code run()} path.
     */
    private final AtomicBoolean shutdownDone = new AtomicBoolean(false);

    /**
     * Launch {@code count} bots asynchronously. Returns immediately after enqueuing all
     * virtual threads — no 30s ceiling (Pitfall 3 fix).
     *
     * <p>Each bot's connect + register lifecycle runs on its own virtual thread. Results
     * are tracked via per-bot {@link CompletableFuture<RegistrationResult>} accessible
     * via {@link #awaitAllSettled()}.
     *
     * @param serverUri WebSocket endpoint
     * @param count     number of bots to launch
     * @param identity  harness identity applied to all bots in this fleet
     * @param rampUp    ramp-up strategy controlling inter-bot launch delay
     * @param mix       species distribution strategy
     * @param factory   bot construction factory (D-19 seam)
     * @return snapshot of the bot list (bots may not be connected yet)
     */
    public List<BotClient> launch(String serverUri, int count, BotIdentity identity,
                                   RampUpSpec rampUp, SpeciesMix mix, BotFactory factory) {
        for (int i = 0; i < count; i++) {
            rampUp.awaitNext(i);
            char species = mix.pickFor(i, count);
            BotClient bot = factory.create(species, identity, Optional.empty(), Optional.empty());

            // Register the onClose hook BEFORE connecting so no close event is missed.
            // CAS gate in BotClient.fireCloseCallbacks ensures decrement fires exactly once
            // even if disconnect() + Jetty @OnWebSocketClose both trigger (Round 2 Codex HIGH).
            bot.onClose(() -> liveCount.decrementAndGet());

            bots.add(bot);
            String harnessTag = identity.harnessId().orElse("op");
            String botId = "fleet-" + harnessTag + "-" + i;
            CompletableFuture<RegistrationResult> fut = new CompletableFuture<>();
            futures.put(botId, fut);

            Thread.startVirtualThread(() -> {
                try {
                    bot.connect();
                    boolean ok = bot.awaitRegistered(15_000L);
                    if (ok) {
                        int live = liveCount.incrementAndGet();
                        highWater.updateAndGet(prev -> Math.max(prev, live));
                    }
                    fut.complete(new RegistrationResult(botId, ok, Optional.empty()));
                } catch (Exception e) {
                    log.warn("Bot {} failed to connect: {}", botId, e.getMessage());
                    fut.complete(new RegistrationResult(botId, false, Optional.of(
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                }
            });
        }
        return List.copyOf(bots);
    }

    /**
     * True JVM-lifetime high-water mark for registered bots. Never decreases.
     * This is the maximum value {@link #currentRegistered()} has reached since the fleet was created.
     */
    public int peakRegistered() {
        return highWater.get();
    }

    /**
     * Live count of currently registered bots.
     *
     * <p><b>Best-effort for the ramp window only (OpenCode review amendment).</b>
     * {@code liveCount} is incremented inside the launch VT when {@code awaitRegistered}
     * succeeds; it is decremented via the {@link BotClient#onClose} hook when a bot
     * disconnects. However, STALLED-pivot reconnects (Phase 17 D-13) bypass the fleet —
     * the {@link BotClient} internal reconnect loop handles those transparently, so the
     * post-reconnect register-success is NOT reflected here.
     *
     * <p>Authoritative steady-state alternatives:
     * <ul>
     *   <li>Server-side: {@code paralife.admission.active.entities{source=harness, harness=<id>}}
     *       (Plan 03 D-12) is the authoritative count of entities currently on the grid.</li>
     *   <li>Client-side per-bot: {@link BotClient#isRegistered()} polling.</li>
     * </ul>
     */
    public int currentRegistered() {
        return liveCount.get();
    }

    /**
     * Returns a {@link CompletableFuture} that completes when all per-bot futures have
     * settled (either registered successfully or failed). Use {@code .get(timeout, unit)}
     * to bound the wait; unlike the old 30s ceiling this is the caller's choice.
     */
    public CompletableFuture<Void> awaitAllSettled() {
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new));
    }

    /** Snapshot of all launched bots. */
    public List<BotClient> getBots() {
        return List.copyOf(bots);
    }

    /**
     * Disconnect all bots and clear internal state.
     *
     * <p><b>Idempotent (Round 2 Claude MEDIUM — moved here from Plan 05).</b>
     * {@link AtomicBoolean#compareAndSet} ensures that double-call from a JVM shutdown hook
     * and the main {@code run()} path is safe — {@link BotClient#disconnect()} is invoked
     * exactly once per bot. The {@code highWater} counter is intentionally NOT reset; it
     * represents the JVM-lifetime peak and is used for final reporting.
     */
    public void shutdown() {
        if (!shutdownDone.compareAndSet(false, true)) {
            return; // already shut down — idempotent
        }
        int size = bots.size();
        for (BotClient b : bots) {
            b.disconnect();
        }
        log.info("BotFleet: {} bots disconnected", size);
        bots.clear();
        futures.clear();
        // liveCount drifts to 0 via onClose callbacks fired by disconnect();
        // do NOT reset highWater — it represents the JVM-lifetime peak.
    }
}
