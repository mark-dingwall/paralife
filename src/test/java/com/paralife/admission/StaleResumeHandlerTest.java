package com.paralife.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.paralife.codec.Frame;
import com.paralife.engine.ActionResolver;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.LiveEntityRegistry;
import com.paralife.engine.MetabolicProfile;
import com.paralife.engine.SpawnConfig;
import com.paralife.engine.TickEngine;
import com.paralife.websocket.RespawnConfig;
import com.paralife.websocket.SessionRegistry;
import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Real token/gate/accounting path; only outbound offers are captured at the queue boundary. */
class StaleResumeHandlerTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AdmissionMetrics metrics = new AdmissionMetrics(meters);
    private final AdmissionConfig config = AdmissionConfig.defaults();
    private final ResumeTokenRegistry tokens = spy(new ResumeTokenRegistry(config, metrics));
    private final BotRegistry bots = spy(new BotRegistry());
    private final OutboundSender outbound = mock(OutboundSender.class);
    private final SessionRegistry sessions = mock(SessionRegistry.class);
    private final WorldGrid grid = mock(WorldGrid.class);
    private final LiveEntityRegistry liveEntities = mock(LiveEntityRegistry.class);
    private WorldWebSocketHandler handler;
    private AdmissionGate gate;
    private WebSocketSession oldSession;
    private WebSocketSession newSession;
    private String oldToken;
    private String collateralToken;
    private String candidate;

    @BeforeEach
    void setUp() {
        TickEngine tick = mock(TickEngine.class);
        when(grid.livingEntityCount()).thenReturn(1);
        gate = new AdmissionGate(config, RespawnConfig.defaults(), grid,
                mock(TickHealthMonitor.class), tokens, metrics);
        gate.seedReservedSlots();
        oldSession = session("old", "operator");
        newSession = session("new", "harness");
        when(sessions.getSession("new")).thenReturn(newSession);
        handler = new WorldWebSocketHandler(sessions, grid, tick, bots, mock(ActionResolver.class),
                MetabolicProfile.defaults(), SpawnConfig.defaults(), RespawnConfig.defaults(), gate,
                outbound, tokens, config, metrics, null, liveEntities);
        newSession.getAttributes().put("harness", "new-harness");
        oldSession.getAttributes().put("entityId", "entity");
        oldSession.getAttributes().put("entityType", 'C');
        oldSession.getAttributes().put("respawnCount", 2);
        oldToken = tokens.issueActive("entity", "old");
        oldSession.getAttributes().put("resumeToken", oldToken);
        metrics.incActiveBucket(oldSession);
        bots.register("old", "entity", new Position(1, 1));
        handler.markStalled(oldSession, 0L);
        collateralToken = tokens.issueActive("entity", "collateral");
        doAnswer(invocation -> {
            candidate = (String) invocation.callRealMethod();
            return candidate;
        }).when(tokens).issueActive("entity", "new");
        clearInvocations(outbound);
    }

    @Test
    void staleRebindReleasesReservationWithoutPublishingSuccess() throws Exception {
        // Deterministically reproduce the missing binding at commit, keeping token-side effects real.
        bots.unregisterByEntity("entity");
        Tags oldTags = metrics.lookupBucketTags("entity");
        Map<String, Object> before = Map.copyOf(newSession.getAttributes());
        assertThat(tokens.stalledSize()).isEqualTo(1);
        assertThat(metrics.totalActiveBucketCount()).isEqualTo(1);
        assertThat(metrics.totalStalledBucketCount()).isEqualTo(1);
        assertThat(gate.reservedSlots()).isEqualTo(1);

        handler.handleMessage(newSession, new TextMessage("r|C|" + oldToken));

        assertThat(newSession.getAttributes()).containsAllEntriesOf(before)
                .doesNotContainKeys("entityId", "entityType", "resumeToken", "respawnCount", "stallTick");
        assertThat(candidate).isNotNull();
        assertThat(tokens.contains(candidate)).isFalse();
        assertThat(tokens.peek(collateralToken).orElseThrow().state()).isEqualTo(ResumeTokenRegistry.State.ACTIVE);
        assertThat(tokens.contains(oldToken)).isFalse();
        assertThat(tokens.stalledSize()).as("tryRebind owns the scalar decrement").isZero();
        assertThat(metrics.totalActiveBucketCount()).isZero();
        assertThat(metrics.totalStalledBucketCount()).isZero();
        assertThat(metrics.lookupBucketTags("entity")).isNotEqualTo(oldTags).isNull();
        assertThat(gate.reservedSlots()).isZero();
        assertThat(meters.get(AdmissionMetrics.M_ACTIVE_ENTITIES).tag("source", "operator").gauge().value())
                .isZero();
        assertThat(meters.get(AdmissionMetrics.M_STALLED_SESSIONS).tag("source", "operator").gauge().value())
                .isZero();
        assertThat(meters.find(AdmissionMetrics.M_ACTIVE_ENTITIES).tag("source", "harness").gauge()).isNull();
        assertThat(meters.counter(AdmissionMetrics.M_REBOUND).count()).isZero();
        assertThat(meters.counter(AdmissionMetrics.M_REJECTED, "reason", "stale-resume-token",
                "source", "harness", "harness", "new-harness").count()).isEqualTo(1);
        ArgumentCaptor<Frame> offers = ArgumentCaptor.forClass(Frame.class);
        verify(outbound).offer(eq("new"), offers.capture());
        assertThat(com.paralife.codec.PerceptionCodec.encode(offers.getValue()))
                .isEqualTo("E|400|stale-resume-token");
        verify(newSession).close();
        verify(newSession, never()).close(any(CloseStatus.class));
        assertThat(bots.getBySession("new")).isEmpty();
    }

    @Test
    void staleRebindAfterIdentityRemapReleasesOriginalReservation() throws Exception {
        handler.onEntityRemapped("old", "entity", "successor");
        assertThat(bots.getBySession("old").orElseThrow().entityId()).isEqualTo("successor");
        assertThat(tokens.peek(oldToken).orElseThrow().entityId()).isEqualTo("successor");
        bots.unregisterByEntity("successor");

        handler.handleMessage(newSession, new TextMessage("r|C|" + oldToken));

        assertThat(gate.reservedSlots()).isZero();
        assertThat(metrics.lookupBucketTags("entity")).isNull();
        assertThat(metrics.lookupBucketTags("successor")).isNull();
        assertThat(metrics.totalActiveBucketCount()).isZero();
        assertThat(metrics.totalStalledBucketCount()).isZero();
        verify(outbound).offer(eq("new"), isA(Frame.ErrorFrame.class));
        verify(outbound, never()).offer(eq("new"), isA(Frame.SyncFrame.class));
    }

    @Test
    void committedRebindPublishesSuccessOnlyAfterRegistryCommit() throws Exception {
        Map<String, Object> before = Map.copyOf(newSession.getAttributes());
        doAnswer(invocation -> {
            assertThat(newSession.getAttributes()).containsAllEntriesOf(before)
                    .doesNotContainKeys("entityId", "entityType", "resumeToken", "respawnCount", "stallTick");
            assertThat(meters.counter(AdmissionMetrics.M_REBOUND).count()).isZero();
            assertThat(metrics.lookupBucketTags("entity")).isEqualTo(Tags.of("source", "operator"));
            return invocation.callRealMethod();
        }).when(bots).rebindSession("new", "entity");

        handler.handleMessage(newSession, new TextMessage("r|C|" + oldToken));

        assertThat(bots.getBySession("new").orElseThrow().entityId()).isEqualTo("entity");
        assertThat(newSession.getAttributes()).containsEntry("entityId", "entity")
                .containsEntry("respawnCount", 2).containsEntry("resumeToken", candidate);
        assertThat(tokens.contains(candidate)).isTrue();
        assertThat(meters.counter(AdmissionMetrics.M_REBOUND).count()).isEqualTo(1);
        assertThat(metrics.totalActiveBucketCount()).isEqualTo(1);
        assertThat(metrics.totalStalledBucketCount()).isZero();
        assertThat(metrics.lookupBucketTags("entity"))
                .isEqualTo(Tags.of("source", "harness", "harness", "new-harness"));
        verify(outbound).offer(eq("new"), isA(Frame.SyncFrame.class));
        verify(newSession, never()).close();
    }

    @Test
    void deathAfterRebindCommitCannotBeOverwrittenBySuccessPublication() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean rebound = (boolean) invocation.callRealMethod();
            assertThat(rebound).isTrue();
            committed.countDown();
            assertThat(publish.await(5, TimeUnit.SECONDS)).as("publication released").isTrue();
            return rebound;
        }).when(bots).rebindSession("new", "entity");

        FutureTask<Void> rebind = new FutureTask<>(() -> {
            handler.handleMessage(newSession, new TextMessage("r|C|" + oldToken));
            return null;
        });
        FutureTask<Void> death = new FutureTask<>(() -> {
            bots.unregisterByEntity("entity");
            assertThat(bots.drainDeaths()).containsExactly(
                    new BotRegistry.DeathNotice("new", "entity", new Position(1, 1)));
            handler.markDead(newSession);
            return null;
        });
        // Platform threads let us observe the exact contended monitor. Separate threads
        // prevent a reentrant markDead call from bypassing the session lock under test.
        Thread rebindThread = Thread.ofPlatform().name("rebind-publication-test").unstarted(rebind);
        Thread deathThread = Thread.ofPlatform().name("rebind-death-test").unstarted(death);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        try {
            rebindThread.start();
            assertThat(committed.await(5, TimeUnit.SECONDS)).as("registry committed").isTrue();
            assertThat(bots.getBySession("new").orElseThrow().entityId()).isEqualTo("entity");
            assertThat(newSession.getAttributes()).doesNotContainKey("entityId");
            assertThat(tokens.contains(candidate)).isTrue();

            deathThread.start();
            // Release publication only after death has either completed (the regression)
            // or reached the session monitor held by the rebind thread (the fixed path).
            await().atMost(5, TimeUnit.SECONDS).until(() -> {
                ThreadInfo info = threads.getThreadInfo(deathThread.threadId());
                return death.isDone() || (info != null
                        && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockOwnerId() == rebindThread.threadId());
            });
            publish.countDown();
            rebind.get(5, TimeUnit.SECONDS);
            death.get(5, TimeUnit.SECONDS);

            assertThat(newSession.getAttributes()).containsEntry("entityType", 'C')
                    .containsEntry("respawnCount", 2)
                    .doesNotContainKeys("entityId", "resumeToken", "stallTick");
            assertThat(bots.getBySession("new")).isEmpty();
            assertThat(tokens.contains(candidate)).isFalse();
            assertThat(metrics.lookupBucketTags("entity")).isNull();
            assertThat(metrics.totalActiveBucketCount()).isZero();
            assertThat(metrics.totalStalledBucketCount()).isZero();
            assertThat(meters.counter(AdmissionMetrics.M_REBOUND).count()).isEqualTo(1);
            assertThat(gate.reservedSlots())
                    .as("death preserves the connected session reservation")
                    .isEqualTo(1);
        } finally {
            publish.countDown();
            rebindThread.join(Duration.ofSeconds(5));
            deathThread.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void terminalCleanupDoesNotContendWithSocketWriteMonitor() throws Exception {
        String entityId = "slow-write-entity";
        String activeToken = tokens.issueActive(entityId, "new");
        newSession.getAttributes().put("entityId", entityId);
        newSession.getAttributes().put("entityType", 'C');
        newSession.getAttributes().put("resumeToken", activeToken);
        metrics.incActiveBucket(newSession);

        CountDownLatch socketMonitorHeld = new CountDownLatch(1);
        CountDownLatch releaseSocketMonitor = new CountDownLatch(1);
        CountDownLatch terminalFinished = new CountDownLatch(1);
        FutureTask<Void> slowWrite = new FutureTask<>(() -> {
            synchronized (newSession) {
                socketMonitorHeld.countDown();
                assertThat(releaseSocketMonitor.await(5, TimeUnit.SECONDS))
                        .as("socket monitor released")
                        .isTrue();
            }
            return null;
        });
        FutureTask<Void> terminalCleanup = new FutureTask<>(() -> {
            try {
                handler.markDead(newSession);
                return null;
            } finally {
                terminalFinished.countDown();
            }
        });
        Thread slowWriteThread = Thread.ofPlatform().name("held-socket-write-test").unstarted(slowWrite);
        Thread terminalThread = Thread.ofPlatform().name("terminal-cleanup-test").unstarted(terminalCleanup);

        boolean completedWithoutSocketMonitor;
        try {
            slowWriteThread.start();
            assertThat(socketMonitorHeld.await(5, TimeUnit.SECONDS)).as("socket monitor held").isTrue();
            terminalThread.start();
            completedWithoutSocketMonitor = terminalFinished.await(1, TimeUnit.SECONDS);
        } finally {
            releaseSocketMonitor.countDown();
            slowWriteThread.join(Duration.ofSeconds(5));
            terminalThread.join(Duration.ofSeconds(5));
        }

        assertThat(completedWithoutSocketMonitor)
                .as("terminal cleanup must not wait for an in-flight socket write")
                .isTrue();
        slowWrite.get(5, TimeUnit.SECONDS);
        terminalCleanup.get(5, TimeUnit.SECONDS);
        assertThat(newSession.getAttributes()).doesNotContainKeys("entityId", "resumeToken");
        assertThat(tokens.contains(activeToken)).isFalse();
    }

    @Test
    void closeDuringPausedRebindPerformsCompleteTerminalCleanup() throws Exception {
        assertTerminalCallbackPerformsCompleteCleanup(
                session -> handler.afterConnectionClosed(session, CloseStatus.NORMAL));
    }

    @Test
    void transportErrorDuringPausedRebindPerformsCompleteTerminalCleanup() throws Exception {
        assertTerminalCallbackPerformsCompleteCleanup(
                session -> handler.handleTransportError(session, new IOException("closed")));
    }

    @Test
    void entityRemapCannotSplitRebindPublication() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean rebound = (boolean) invocation.callRealMethod();
            assertThat(rebound).isTrue();
            committed.countDown();
            assertThat(publish.await(5, TimeUnit.SECONDS)).as("publication released").isTrue();
            return rebound;
        }).when(bots).rebindSession("new", "entity");

        FutureTask<Void> rebind = new FutureTask<>(() -> {
            handler.handleMessage(newSession, new TextMessage("r|C|" + oldToken));
            return null;
        });
        FutureTask<Void> remap = new FutureTask<>(() -> {
            handler.onEntityRemapped("new", "entity", "successor");
            return null;
        });
        Thread rebindThread = Thread.ofPlatform().name("remap-rebind-test").unstarted(rebind);
        Thread remapThread = Thread.ofPlatform().name("remap-publication-test").unstarted(remap);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        try {
            rebindThread.start();
            assertThat(committed.await(5, TimeUnit.SECONDS)).as("registry committed").isTrue();
            remapThread.start();
            await().atMost(5, TimeUnit.SECONDS).until(() -> {
                ThreadInfo info = threads.getThreadInfo(remapThread.threadId());
                return remap.isDone() || (info != null
                        && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockOwnerId() == rebindThread.threadId());
            });

            publish.countDown();
            rebind.get(5, TimeUnit.SECONDS);
            remap.get(5, TimeUnit.SECONDS);

            assertThat(bots.getBySession("new").orElseThrow().entityId()).isEqualTo("successor");
            assertThat(newSession.getAttributes()).containsEntry("entityId", "successor");
            assertThat(tokens.peek(candidate).orElseThrow().entityId()).isEqualTo("successor");
            assertThat(metrics.lookupBucketTags("entity")).isNull();
            assertThat(metrics.lookupBucketTags("successor"))
                    .isEqualTo(Tags.of("source", "harness", "harness", "new-harness"));
            assertThat(metrics.totalActiveBucketCount()).isEqualTo(1);
            assertThat(metrics.totalStalledBucketCount()).isZero();
        } finally {
            publish.countDown();
            rebindThread.join(Duration.ofSeconds(5));
            remapThread.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void markStalledCannotSplitRebindPublication() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean rebound = (boolean) invocation.callRealMethod();
            assertThat(rebound).isTrue();
            committed.countDown();
            assertThat(publish.await(5, TimeUnit.SECONDS)).as("publication released").isTrue();
            return rebound;
        }).when(bots).rebindSession("new", "entity");

        FutureTask<Void> rebind = new FutureTask<>(() -> {
            handler.handleMessage(newSession, new TextMessage("r|C|" + oldToken));
            return null;
        });
        FutureTask<Void> stall = new FutureTask<>(() -> {
            handler.markStalled(newSession, 1L);
            return null;
        });
        Thread rebindThread = Thread.ofPlatform().name("stall-rebind-test").unstarted(rebind);
        Thread stallThread = Thread.ofPlatform().name("stall-publication-test").unstarted(stall);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        try {
            rebindThread.start();
            assertThat(committed.await(5, TimeUnit.SECONDS)).as("registry committed").isTrue();
            stallThread.start();
            await().atMost(5, TimeUnit.SECONDS).until(() -> {
                ThreadInfo info = threads.getThreadInfo(stallThread.threadId());
                return stall.isDone() || (info != null
                        && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockOwnerId() == rebindThread.threadId());
            });
            assertThat(stall.isDone()).as("stall transition waits for rebind publication").isFalse();

            publish.countDown();
            rebind.get(5, TimeUnit.SECONDS);
            stall.get(5, TimeUnit.SECONDS);

            assertThat(newSession.getAttributes()).containsEntry("stallTick", 1L)
                    .doesNotContainKey("entityId");
            assertThat(tokens.peek(candidate).orElseThrow().state())
                    .isEqualTo(ResumeTokenRegistry.State.STALLED);
            verify(outbound).detachSession(newSession, CloseStatus.SERVICE_RESTARTED);
        } finally {
            publish.countDown();
            rebindThread.join(Duration.ofSeconds(5));
            stallThread.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void stalledInboundSendFailureStillCountsRejectionAndClosesForRestart() throws Exception {
        clearInvocations(oldSession);
        doThrow(new IOException("transport failed")).when(oldSession).sendMessage(any());

        handler.handleMessage(oldSession, new TextMessage("r|C"));

        verify(oldSession).isOpen();
        verify(oldSession).sendMessage(argThat(message -> message.getPayload().equals("E|408|reconnect-required")));
        assertThat(meters.counter(AdmissionMetrics.M_REJECTED, "reason", "reconnect-required",
                "source", "operator").count()).isEqualTo(1);
        verify(oldSession).close(CloseStatus.SERVICE_RESTARTED);
    }

    private void assertTerminalCallbackPerformsCompleteCleanup(
            Consumer<WebSocketSession> terminalCallback) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean rebound = (boolean) invocation.callRealMethod();
            assertThat(rebound).isTrue();
            committed.countDown();
            assertThat(publish.await(5, TimeUnit.SECONDS)).as("publication released").isTrue();
            return rebound;
        }).when(bots).rebindSession("new", "entity");

        FutureTask<Void> rebind = new FutureTask<>(() -> {
            handler.handleMessage(newSession, new TextMessage("r|C|" + oldToken));
            return null;
        });
        FutureTask<Void> terminal = new FutureTask<>(() -> {
            terminalCallback.accept(newSession);
            return null;
        });
        Thread rebindThread = Thread.ofPlatform().name("terminal-rebind-test").unstarted(rebind);
        Thread cleanupThread = Thread.ofPlatform().name("terminal-cleanup-test").unstarted(terminal);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        try {
            rebindThread.start();
            assertThat(committed.await(5, TimeUnit.SECONDS)).as("registry committed").isTrue();

            cleanupThread.start();
            await().atMost(5, TimeUnit.SECONDS).until(() -> {
                ThreadInfo info = threads.getThreadInfo(cleanupThread.threadId());
                return terminal.isDone() || (info != null
                        && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockOwnerId() == rebindThread.threadId());
            });

            publish.countDown();
            rebind.get(5, TimeUnit.SECONDS);
            terminal.get(5, TimeUnit.SECONDS);

            assertThat(newSession.getAttributes()).doesNotContainKeys(
                    "entityId", "entityType", "resumeToken", "stallTick");
            assertThat(bots.getBySession("new")).isEmpty();
            assertThat(tokens.contains(candidate)).isFalse();
            assertThat(metrics.lookupBucketTags("entity")).isNull();
            assertThat(metrics.totalActiveBucketCount()).isZero();
            assertThat(metrics.totalStalledBucketCount()).isZero();
            assertThat(gate.reservedSlots()).isZero();
            verify(liveEntities).unregister("entity");
        } finally {
            publish.countDown();
            rebindThread.join(Duration.ofSeconds(5));
            cleanupThread.join(Duration.ofSeconds(5));
        }
    }

    private static WebSocketSession session(String id, String source) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put("source", source);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }
}
