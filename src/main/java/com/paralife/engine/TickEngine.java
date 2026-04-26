package com.paralife.engine;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The heartbeat of the simulation. Runs a tick loop on a virtual thread,
 * publishing TickEvents at a configurable rate.
 *
 * <p>The engine uses a simple sleep-based loop rather than ScheduledExecutorService
 * to maintain precise control over tick timing and shutdown behavior.
 */
@Component
public class TickEngine {

    private static final Logger log = LoggerFactory.getLogger(TickEngine.class);

    private final TickConfig config;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final DistributionSummary tickWork;
    private volatile Thread tickThread;
    private volatile long lastTickWorkMs;
    private volatile long currentTick;

    @Autowired
    public TickEngine(TickConfig config, ApplicationEventPublisher eventPublisher,
                      MeterRegistry meterRegistry) {
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.tickWork = DistributionSummary.builder("paralife.tick.work.ms")
                .description("Per-tick wall-clock work time end-to-end (listener dispatch + all @Order slots)")
                .baseUnit("ms")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Back-compat overload for unit tests that construct TickEngine directly
     * without a MeterRegistry bean. Defaults to a SimpleMeterRegistry so the
     * tickWork DistributionSummary is always non-null.
     */
    public TickEngine(TickConfig config, ApplicationEventPublisher eventPublisher) {
        this(config, eventPublisher, new SimpleMeterRegistry());
    }

    @PostConstruct
    void init() {
        if (config.autoStart()) {
            start();
        }
    }

    /**
     * Starts the tick loop on a virtual thread.
     * Idempotent — calling when already running does nothing.
     */
    public synchronized void start() {
        if (running.get()) {
            log.warn("Tick engine already running");
            return;
        }
        running.set(true);
        tickThread = Thread.startVirtualThread(this::tickLoop);
        tickThread.setName("tick-engine");
        log.info("Tick engine started (interval={}ms)", config.intervalMs());
    }

    /**
     * Stops the tick loop gracefully. Waits for the current tick to complete.
     */
    @PreDestroy
    public synchronized void stop() {
        if (!running.get()) {
            return;
        }
        log.info("Tick engine stopping after tick {}", tickCounter.get());
        running.set(false);
        if (tickThread != null) {
            tickThread.interrupt();
            try {
                tickThread.join(config.intervalMs() * 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Tick engine stopped");
    }

    private void tickLoop() {
        log.info("Tick loop started on thread: {} (virtual={})",
                Thread.currentThread().getName(), Thread.currentThread().isVirtual());

        while (running.get()) {
            try {
                long tickNumber = tickCounter.incrementAndGet();
                long startTime = System.nanoTime();

                this.currentTick = tickNumber;  // visible to same-tick @EventListener readers
                var event = new TickEvent(tickNumber);
                eventPublisher.publishEvent(event);

                long elapsedNs = System.nanoTime() - startTime;
                tickWork.record(elapsedNs / 1_000_000.0);
                long elapsed = elapsedNs / 1_000_000;
                this.lastTickWorkMs = elapsed;  // written after publishEvent; readers see tick N-1
                long sleepTime = Math.max(0, config.intervalMs() - elapsed);

                if (elapsed > config.intervalMs()) {
                    log.warn("Tick {} took {}ms (exceeds interval of {}ms)",
                            tickNumber, elapsed, config.intervalMs());
                }

                if (sleepTime > 0 && running.get()) {
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in tick {}: {}", tickCounter.get(), e.getMessage(), e);
            }
        }
    }

    public long getCurrentTick() {
        return tickCounter.get();
    }

    /**
     * Wall-clock work time in ms for the most recently COMPLETED tick.
     *
     * <p>Note: same-{@code TickEvent}-listener readers see tick N-1's value during the dispatch
     * for tick N (because this field is written after {@code publishEvent} returns). This is
     * acceptable for the rolling-mean hysteresis use-case in {@link com.paralife.admission.TickHealthMonitor}
     * — the gauge runs ~1 tick behind, with no correctness impact on hysteresis transitions.
     */
    public long getLastTickWorkMs() { return lastTickWorkMs; }

    /**
     * The tick number currently being dispatched. Written before {@code publishEvent} so
     * same-{@code TickEvent} listeners (e.g. Plan 07 markStalled, Plan 05 ResumeTokenRegistry)
     * read the correct tick number for the in-flight event.
     */
    public long currentTick() { return currentTick; }

    public boolean isRunning() {
        return running.get();
    }
}
