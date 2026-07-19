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

        sender.attach(s);
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
        sender.attach(s);
        assertThat(sender.attachedCount()).isEqualTo(1);
        assertThat(sender.isDraining("obs2")).isTrue();

        sender.detach("obs2");

        assertThat(sender.attachedCount()).as("session removed on detach").isEqualTo(0);
        // control: a still-attached, non-detached session stays draining
        WebSocketSession s3 = openSession("obs3");
        sender.attach(s3);
        assertThat(sender.isDraining("obs3")).as("un-detached observer remains").isTrue();
        sender.detach("obs3");
    }

    @Test
    void detachClosesTransportSoADrainStalledInSendActuallyTerminates() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        CountDownLatch inSend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WebSocketSession s = openSession("obs4");
        // Drain stalls inside sendMessage; session.close() (invoked by detach) unblocks it —
        // this simulates the real Jetty behaviour the OutboundSender docs describe.
        doAnswer(inv -> {
            inSend.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        doAnswer(inv -> { release.countDown(); when(s.isOpen()).thenReturn(false); return null; })
                .when(s).close(org.mockito.ArgumentMatchers.any());

        sender.attach(s);
        sender.offer("obs4", "f1"); // taken → drain stalls in send
        assertThat(inSend.await(2, TimeUnit.SECONDS)).isTrue();

        Thread drain = sender.threadForTest("obs4"); // capture BEFORE detach removes it
        sender.detach(s);                            // close-first → unblocks send, then interrupt
        drain.join(2000);

        assertThat(drain.isAlive())
                .as("a drain stalled in a Jetty write terminates once the transport is closed")
                .isFalse();
    }

    @Test
    void sendIOExceptionClosesTransportAndTerminatesDrain() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = openSession("obs5");
        doThrow(new IOException("broken")).when(s).sendMessage(org.mockito.ArgumentMatchers.any());

        sender.attach(s);
        Thread drain = sender.threadForTest("obs5");
        sender.offer("obs5", "f1"); // drain takes it → send throws IOException → close + exit

        drain.join(2000);
        assertThat(drain.isAlive())
                .as("drain exits on an unrecoverable send failure — no dead-socket spin").isFalse();
        verify(s).close(org.mockito.ArgumentMatchers.any()); // close → Jetty callback → handler cleanup
    }
}
