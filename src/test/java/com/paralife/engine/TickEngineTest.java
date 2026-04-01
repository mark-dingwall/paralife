package com.paralife.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TickEngineTest {

    private TickEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
    }

    @Test
    void startsAndPublishesTickEvents() throws Exception {
        var events = new CopyOnWriteArrayList<TickEvent>();
        var latch = new CountDownLatch(3); // Wait for 3 ticks

        ApplicationEventPublisher publisher = event -> {
            if (event instanceof TickEvent te) {
                events.add(te);
                latch.countDown();
            }
        };

        // Fast tick rate for testing (50ms)
        engine = new TickEngine(new TickConfig(50, false), publisher);
        engine.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);

        // Tick numbers should be sequential
        assertThat(events.get(0).tickNumber()).isEqualTo(1);
        assertThat(events.get(1).tickNumber()).isEqualTo(2);
        assertThat(events.get(2).tickNumber()).isEqualTo(3);
    }

    @Test
    void stopsGracefully() throws Exception {
        var latch = new CountDownLatch(1);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof TickEvent) latch.countDown();
        };

        engine = new TickEngine(new TickConfig(50, false), publisher);
        engine.start();
        assertThat(engine.isRunning()).isTrue();

        latch.await(1, TimeUnit.SECONDS);
        engine.stop();

        assertThat(engine.isRunning()).isFalse();
        long tickAtStop = engine.getCurrentTick();

        // Wait a bit and verify no more ticks happened
        Thread.sleep(200);
        assertThat(engine.getCurrentTick()).isEqualTo(tickAtStop);
    }

    @Test
    void startIsIdempotent() {
        ApplicationEventPublisher publisher = event -> {};
        engine = new TickEngine(new TickConfig(500, false), publisher);
        engine.start();
        engine.start(); // Should not throw or create second thread
        assertThat(engine.isRunning()).isTrue();
    }

    @Test
    void stopWhenNotRunningIsNoop() {
        ApplicationEventPublisher publisher = event -> {};
        engine = new TickEngine(new TickConfig(500, false), publisher);
        engine.stop(); // Should not throw
        assertThat(engine.isRunning()).isFalse();
    }

    @Test
    void tickCounterIncrementsCorrectly() throws Exception {
        var latch = new CountDownLatch(5);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof TickEvent) latch.countDown();
        };

        engine = new TickEngine(new TickConfig(20, false), publisher);
        assertThat(engine.getCurrentTick()).isEqualTo(0);

        engine.start();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(engine.getCurrentTick()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void runsOnVirtualThread() throws Exception {
        var threadRef = new Thread[1];
        var latch = new CountDownLatch(1);

        ApplicationEventPublisher publisher = event -> {
            if (event instanceof TickEvent) {
                threadRef[0] = Thread.currentThread();
                latch.countDown();
            }
        };

        engine = new TickEngine(new TickConfig(50, false), publisher);
        engine.start();
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        // The tick event is published on the tick thread, which should be virtual
        assertThat(threadRef[0].isVirtual()).isTrue();
    }
}
