package com.paralife.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private volatile Thread tickThread;

    public TickEngine(TickConfig config, ApplicationEventPublisher eventPublisher) {
        this.config = config;
        this.eventPublisher = eventPublisher;
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

                var event = new TickEvent(tickNumber);
                eventPublisher.publishEvent(event);

                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
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

    public boolean isRunning() {
        return running.get();
    }
}
