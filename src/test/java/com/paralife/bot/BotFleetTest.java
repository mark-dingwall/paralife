package com.paralife.bot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link BotFleet} — async VT-per-bot launcher.
 * Tests run against the full embedded Spring server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BotFleetTest {

    @LocalServerPort
    int port;

    private BotFleet fleet;
    private BotFactory factory;
    private String serverUri;

    // Generous safety-net for the bot connect/settle handshake. This is PLUMBING to
    // reach each test's real assertion, not a behavioural deadline — under a 2-core
    // VT-carrier squeeze a single bot's Jetty cold-start handshake was observed to
    // exceed a tight 10s (forkEvery=0 stress sweep, this class:onCloseHookFiresExactlyOnce).
    // A safety-net only exists to bound a genuine hang, so it should be generous, not
    // tuned to the happy path. (Same class of fix as TD-22-D for HundredBotIntegrationTest.)
    // Deliberately NOT applied to awaitAllSettled_completesWithin5Seconds (a real 5s SLA
    // assertion) or the badFleet negative-path waits (which expect failure).
    private static final long SETTLE_TIMEOUT_S = 30;

    @BeforeEach
    void setUp() {
        serverUri = "ws://localhost:" + port + "/ws/world";
        fleet = new BotFleet();
        factory = new BotFactory(serverUri);
    }

    @AfterEach
    void tearDown() {
        if (fleet != null) {
            fleet.shutdown();
        }
    }

    @Test
    void launch_returns5Bots_immediately() {
        List<BotClient> bots = fleet.launch(serverUri, 5, BotIdentity.operator(),
                RampUpSpec.instant(), SpeciesMix.balanced(), factory);
        assertThat(bots).hasSize(5);
    }

    @Test
    void awaitAllSettled_completesWithin5Seconds() throws Exception {
        fleet.launch(serverUri, 5, BotIdentity.operator(),
                RampUpSpec.instant(), SpeciesMix.balanced(), factory);
        fleet.awaitAllSettled().get(5, TimeUnit.SECONDS);
        // no TimeoutException = test passes
    }

    @Test
    void no30sTimeoutWarningEmitted_forSmallLaunch() throws Exception {
        // Pitfall 3 lock: old BotLauncher emitted "Not all bots finished connecting within timeout"
        // after 30s. BotFleet must NEVER emit this line.
        Logger fleetLogger = (Logger) LoggerFactory.getLogger(BotFleet.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        fleetLogger.addAppender(appender);
        try {
            fleet.launch(serverUri, 5, BotIdentity.operator(),
                    RampUpSpec.instant(), SpeciesMix.balanced(), factory);
            fleet.awaitAllSettled().get(SETTLE_TIMEOUT_S, TimeUnit.SECONDS);

            boolean badLogFound = appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("Not all bots finished connecting within timeout"));
            assertThat(badLogFound).as("BotFleet must not emit the 30s-timeout warning").isFalse();
        } finally {
            fleetLogger.detachAppender(appender);
        }
    }

    @Test
    void peakIsTrueHighWaterMark() throws Exception {
        fleet.launch(serverUri, 5, BotIdentity.operator(),
                RampUpSpec.instant(), SpeciesMix.balanced(), factory);
        fleet.awaitAllSettled().get(SETTLE_TIMEOUT_S, TimeUnit.SECONDS);

        // All bots should have registered
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> fleet.currentRegistered() == 5);
        assertThat(fleet.peakRegistered()).isEqualTo(5);
        assertThat(fleet.currentRegistered()).isEqualTo(5);

        // Disconnect one bot — peak should remain 5
        fleet.getBots().get(0).disconnect();
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> fleet.currentRegistered() == 4);
        assertThat(fleet.peakRegistered()).isEqualTo(5);
        assertThat(fleet.currentRegistered()).isEqualTo(4);
    }

    @Test
    void shutdownIsIdempotent() throws Exception {
        fleet.launch(serverUri, 3, BotIdentity.operator(),
                RampUpSpec.instant(), SpeciesMix.balanced(), factory);
        fleet.awaitAllSettled().get(SETTLE_TIMEOUT_S, TimeUnit.SECONDS);

        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch bothDone = new CountDownLatch(2);
        List<Throwable> errors = new ArrayList<>();

        Runnable shutdownTask = () -> {
            bothStarted.countDown();
            try {
                bothStarted.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                fleet.shutdown();
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                bothDone.countDown();
            }
        };

        Thread t1 = Thread.startVirtualThread(shutdownTask);
        Thread t2 = Thread.startVirtualThread(shutdownTask);
        assertThat(bothDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
    }

    @Test
    void identityPropagation_allBotsCarryHarnessIdentity() throws Exception {
        BotIdentity identity = BotIdentity.harness("test-fleet");
        List<BotClient> bots = fleet.launch(serverUri, 3, identity,
                RampUpSpec.instant(), SpeciesMix.balanced(), factory);

        assertThat(bots).hasSize(3);
        bots.forEach(b -> assertThat(b.identity()).isEqualTo(identity));
    }

    @Test
    void onCloseHook_firesOnExplicitDisconnect() throws Exception {
        // Start a single bot and register a close callback.
        List<BotClient> bots = fleet.launch(serverUri, 1, BotIdentity.operator(),
                RampUpSpec.instant(), SpeciesMix.balanced(), factory);
        fleet.awaitAllSettled().get(SETTLE_TIMEOUT_S, TimeUnit.SECONDS);

        BotClient bot = bots.get(0);
        CountDownLatch closedLatch = new CountDownLatch(1);
        AtomicInteger fireCount = new AtomicInteger(0);
        bot.onClose(() -> {
            fireCount.incrementAndGet();
            closedLatch.countDown();
        });

        bot.disconnect();
        assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fireCount.get()).isEqualTo(1);
    }

    @Test
    void onCloseHookFiresExactlyOnce_concurrentDisconnectAndRemoteClose() throws Exception {
        // Round 2 Codex HIGH: CAS-guarded fire-once. Even if both disconnect() and the
        // Jetty @OnWebSocketClose path fire, callbacks must run EXACTLY ONCE.
        List<BotClient> bots = fleet.launch(serverUri, 1, BotIdentity.operator(),
                RampUpSpec.instant(), SpeciesMix.balanced(), factory);
        fleet.awaitAllSettled().get(SETTLE_TIMEOUT_S, TimeUnit.SECONDS);

        BotClient bot = bots.get(0);
        AtomicInteger fireCount = new AtomicInteger(0);
        CountDownLatch doneLatch = new CountDownLatch(1);
        bot.onClose(() -> {
            fireCount.incrementAndGet();
            doneLatch.countDown();
        });

        // Disconnect from two concurrent threads — only one should win the CAS gate.
        CountDownLatch startGate = new CountDownLatch(1);
        Thread t1 = Thread.startVirtualThread(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            bot.disconnect();
        });
        Thread t2 = Thread.startVirtualThread(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            bot.disconnect();
        });
        startGate.countDown();

        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        // Give concurrent thread a moment to also try
        Thread.sleep(200);
        assertThat(fireCount.get()).as("close callback must fire EXACTLY ONCE").isEqualTo(1);
    }

    @Test
    void failedBot_hasRegistrationResultWithRegisteredFalse() throws Exception {
        // Connect to an invalid URI — bot should fail to connect with registered=false.
        BotFleet badFleet = new BotFleet();
        BotFactory badFactory = new BotFactory("ws://localhost:1/ws/world");
        try {
            badFleet.launch("ws://localhost:1/ws/world", 1, BotIdentity.operator(),
                    RampUpSpec.instant(), SpeciesMix.balanced(), badFactory);
            var result = badFleet.awaitAllSettled().get(5, TimeUnit.SECONDS);
            // Just verifying it completes without hanging (the future resolves with failure)
        } finally {
            badFleet.shutdown();
        }
    }

    @Test
    void connectFailures_doNotUnderflowLiveCount() throws Exception {
        // H-01 (Round B): when bots fail to register (connect rejected or timeout),
        // BotFleet.shutdown() still calls disconnect() on every bot in the list. The
        // close-callback decrement must NOT fire for bots that never registered, so
        // currentRegistered() must end at 0, not negative.
        BotFleet badFleet = new BotFleet();
        // Port 1 (tcpmux) is reserved and effectively never open — connect will fail.
        BotFactory badFactory = new BotFactory("ws://localhost:1/ws/world");
        try {
            badFleet.launch("ws://localhost:1/ws/world", 5, BotIdentity.operator(),
                    RampUpSpec.instant(), SpeciesMix.balanced(), badFactory);
            badFleet.awaitAllSettled().get(20, TimeUnit.SECONDS);
            badFleet.shutdown();
            assertThat(badFleet.currentRegistered())
                    .as("liveCount must not underflow when all 5 bots fail to register")
                    .isEqualTo(0);
        } finally {
            badFleet.shutdown();
        }
    }

    @Test
    void rateSpec50_10Bots_allStartWithin1Second() throws Exception {
        long start = System.currentTimeMillis();
        fleet.launch(serverUri, 10, BotIdentity.operator(),
                RampUpSpec.rate(50), SpeciesMix.balanced(), factory);
        long elapsed = System.currentTimeMillis() - start;

        // At 50/s with 10 bots, ramp should finish in ~200ms (9 intervals × 20ms each)
        // We allow 1s of wall-clock slack for VT scheduling.
        assertThat(elapsed).isLessThan(1000L);
        fleet.awaitAllSettled().get(SETTLE_TIMEOUT_S, TimeUnit.SECONDS);
    }

    @Test
    void waveSpec_producesGapBetweenWaves() throws Exception {
        long start = System.currentTimeMillis();
        fleet.launch(serverUri, 10, BotIdentity.operator(),
                RampUpSpec.wave(5, 200L), SpeciesMix.balanced(), factory);
        long elapsed = System.currentTimeMillis() - start;

        // Wave of 5 then 200ms sleep then wave of 5; total >= 200ms.
        assertThat(elapsed).isGreaterThanOrEqualTo(200L);
        fleet.awaitAllSettled().get(SETTLE_TIMEOUT_S, TimeUnit.SECONDS);
    }
}
