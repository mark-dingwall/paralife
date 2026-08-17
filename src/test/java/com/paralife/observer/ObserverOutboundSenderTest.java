package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ObserverOutboundSenderTest {

    private static WebSocketSession openSession(String id) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void latestWinsWhileDrainStalled_offerNeverBlocks() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        CopyOnWriteArrayList<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch firstSendEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        WebSocketSession s = openSession("obs1");
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            firstSendEntered.countDown();
            release.await(2, TimeUnit.SECONDS); // stall the drain VT inside the first send
            return null;
        }).when(s).sendMessage(org.mockito.ArgumentMatchers.any());

        sender.attach(s, () -> {});
        sender.offer("obs1", "frame-1"); // taken by drain, stalls in send
        assertThat(firstSendEntered.await(2, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        sender.offer("obs1", "frame-2"); // slot
        sender.offer("obs1", "frame-3"); // overwrites frame-2 (latest-wins)
        long offerNanos = System.nanoTime() - t0;
        assertThat(offerNanos).as("offer is non-blocking even while drain is stalled")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(200));

        release.countDown(); // drain resumes → takes frame-3 (frame-2 was dropped)
        Thread.sleep(200);
        assertThat(sent).as("stale frame-2 coalesced away; newest wins")
                .containsExactly("frame-1", "frame-3");
        sender.detach("obs1");
    }

    @Test
    void detachInterruptsDrainAndRemovesSession() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = openSession("obs2");
        sender.attach(s, () -> {});
        assertThat(sender.attachedCount()).isEqualTo(1);
        assertThat(sender.isDraining("obs2")).isTrue();

        sender.detach("obs2");

        assertThat(sender.attachedCount()).as("session removed on detach").isEqualTo(0);
        // control: a still-attached, non-detached session stays draining
        WebSocketSession s3 = openSession("obs3");
        sender.attach(s3, () -> {});
        assertThat(sender.isDraining("obs3")).as("un-detached observer remains").isTrue();
        sender.detach("obs3");
    }

    @Test
    void detachClosesTransportSoADrainStalledInSendActuallyTerminates() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        CountDownLatch inSend = new CountDownLatch(1);
        // The stall is UNINTERRUPTIBLE (a held ReentrantLock, mirroring a real Jetty blocking write
        // that ignores Thread.interrupt): ONLY session.close() unblocks it. This pins close-FIRST,
        // not merely interrupt — delete production close() and detach's interrupt alone can no
        // longer free the drain, so the join below fails. (The prior interruptible CountDownLatch
        // stall stayed green even with close-first removed.)
        ReentrantLock writeGate = new ReentrantLock();
        writeGate.lock(); // test holds it; the drain blocks acquiring it inside sendMessage
        AtomicBoolean terminalCleanup = new AtomicBoolean(false);
        WebSocketSession s = openSession("obs4");
        doAnswer(inv -> {
            inSend.countDown();
            writeGate.lock();   // uninterruptible stall until close() releases the gate
            writeGate.unlock();
            return null;
        }).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        doAnswer(inv -> { when(s.isOpen()).thenReturn(false); writeGate.unlock(); return null; })
                .when(s).close(org.mockito.ArgumentMatchers.any());

        sender.attach(s, () -> terminalCleanup.set(true));
        sender.offer("obs4", "f1"); // taken → drain stalls in send
        assertThat(inSend.await(2, TimeUnit.SECONDS)).isTrue();

        Thread drain = sender.threadForTest("obs4"); // capture BEFORE detach removes it
        sender.detach(s);                            // close-FIRST → unblocks the write, then interrupt
        drain.join(2000);

        assertThat(drain.isAlive())
                .as("a drain in an uninterruptible write terminates only once close() fires").isFalse();
        verify(s).close(org.mockito.ArgumentMatchers.any()); // pins close-first, not interrupt-only
        assertThat(terminalCleanup)
                .as("external detach is not a drain-initiated failure — callback must NOT run").isFalse();
    }

    @Test
    void sendIOExceptionClosesTransportAndTerminatesDrain() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = openSession("obs5");
        doThrow(new IOException("broken")).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        CountDownLatch cleaned = new CountDownLatch(1);

        sender.attach(s, cleaned::countDown);
        Thread drain = sender.threadForTest("obs5");
        sender.offer("obs5", "f1"); // drain takes it → send throws IOException → close + cleanup + exit

        drain.join(2000);
        assertThat(drain.isAlive())
                .as("drain exits on an unrecoverable send failure — no dead-socket spin").isFalse();
        verify(s).close(org.mockito.ArgumentMatchers.any());
        assertThat(cleaned.await(2, TimeUnit.SECONDS))
                .as("drain runs its own terminal cleanup on IOException").isTrue();
    }

    @Test
    void terminalSendFailureRunsCleanupEvenWhenCloseAlsoFails() throws Exception {
        // C-2: the drain must OWN its teardown. On a terminal send failure it invokes the cleanup
        // callback (unregister + detach + release) DIRECTLY — not via a Jetty close callback that
        // may never fire. Here close() itself throws, so ONLY the drain-owned callback can complete
        // teardown; without it the permit + broadcaster registration + sender state would leak.
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = openSession("obs6");
        doThrow(new IOException("broken")).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        doThrow(new RuntimeException("close failed too")).when(s).close(org.mockito.ArgumentMatchers.any());
        CountDownLatch cleaned = new CountDownLatch(1);

        sender.attach(s, cleaned::countDown);
        Thread drain = sender.threadForTest("obs6");
        sender.offer("obs6", "f1");

        assertThat(cleaned.await(2, TimeUnit.SECONDS))
                .as("cleanup runs even though close() threw").isTrue();
        drain.join(2000);
        assertThat(drain.isAlive()).as("drain still exits").isFalse();
    }

    @Test
    void persistentRuntimeExceptionIsTerminalNotAnInfiniteRetry() throws Exception {
        // C-2: a persistent (non-IO) RuntimeException from sendMessage must terminate the drain and
        // run cleanup, not spin re-sending every tick with no reaper. (Old behaviour: log-and-continue.)
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = openSession("obs7");
        doThrow(new RuntimeException("persistent")).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        CountDownLatch cleaned = new CountDownLatch(1);

        sender.attach(s, cleaned::countDown);
        Thread drain = sender.threadForTest("obs7");
        sender.offer("obs7", "f1");

        assertThat(cleaned.await(2, TimeUnit.SECONDS))
                .as("RuntimeException is terminal → cleanup + exit, no forever-loop").isTrue();
        drain.join(2000);
        assertThat(drain.isAlive()).isFalse();
    }

    @Test
    void drainSelfHealsWhenSessionClosedWithoutAContainerCallback() throws Exception {
        // #2: the drain-owned self-heal is the observer's ONLY backup reaper when a container close
        // callback never fires (unlike the bot sender there is no admission FSM / grace sweep). A
        // session found already-closed must still route through terminal cleanup — skipping the
        // doomed send with `if(!isOpen()) continue` would park the drain on take() and strand the
        // permit + broadcaster registration forever. (RED with that guard present.)
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn("obs-closed");
        when(s.isOpen()).thenReturn(false); // closed, but no onClose/onError fired
        doThrow(new IOException("closed")).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        CountDownLatch cleaned = new CountDownLatch(1);

        sender.attach(s, cleaned::countDown);
        Thread drain = sender.threadForTest("obs-closed");
        sender.offer("obs-closed", "f1");

        assertThat(cleaned.await(2, TimeUnit.SECONDS))
                .as("a closed-without-callback session still self-heals via terminal cleanup").isTrue();
        drain.join(2000);
        assertThat(drain.isAlive()).isFalse();
    }

    @Test
    void plainDetachDoesNotInvokeTheTerminalFailureCallback() throws Exception {
        // control isolating the terminal-failure path: an external detach (handler close / shutdown)
        // is NOT a drain-initiated failure — the caller already owns cleanup — so the callback must
        // not fire. Pairs with the terminal-failure tests above (which prove it DOES fire on a break).
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = openSession("obs8");
        AtomicBoolean called = new AtomicBoolean(false);

        sender.attach(s, () -> called.set(true));
        sender.detach("obs8");
        Thread.sleep(150); // give any errant callback time to fire

        assertThat(called).as("plain detach is not a terminal send failure").isFalse();
    }
}
