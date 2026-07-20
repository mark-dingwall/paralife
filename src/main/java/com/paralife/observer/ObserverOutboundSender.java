package com.paralife.observer;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Off-thread delivery for observers (C2 analog of OutboundSender, NOT routed through
 * the bot STALLED/resume FSM). One drain virtual-thread per observer + a capacity-1
 * latest-wins mailbox. {@link #offer} from the tick thread is non-blocking and
 * overwrites any unsent frame (a slow tab shows the newest world, never a backlog).
 * The drain VT does the {@code synchronized(session)} send. Closed only on
 * detach (transport error / handler close / shutdown) — never on lag.
 */
@Component
public class ObserverOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(ObserverOutboundSender.class);

    private final Map<String, LinkedBlockingQueue<String>> slots = new ConcurrentHashMap<>();
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * @param onTerminalFailure drain-owned cleanup (unregister + detach + release-once) run when a
     *     send fails terminally — invoked DIRECTLY rather than trusting a Jetty close callback that
     *     may never fire (e.g. if {@code close()} itself throws). Not run on a normal external detach.
     */
    public void attach(WebSocketSession session, Runnable onTerminalFailure) {
        String id = session.getId();
        detach(id); // idempotent re-attach
        LinkedBlockingQueue<String> slot = new LinkedBlockingQueue<>(1);
        // Publish the thread handle BEFORE starting it (create it unstarted). The only detach that
        // can race an in-flight attach is @PreDestroy shutdown scanning `sessions`; putting the
        // thread + slot in FIRST and `sessions` LAST guarantees that once shutdown can see the
        // session, it can also find and interrupt the drain. An interrupted unstarted VT exits
        // immediately on start (the while-guard sees the interrupt), so no drain is orphaned.
        Thread t = Thread.ofVirtual().name("ws-observer-" + id).unstarted(() -> drain(session, slot, onTerminalFailure));
        slots.put(id, slot);
        threads.put(id, t);
        sessions.put(id, session); // retained so @PreDestroy can close-first (interrupt alone
                                   // cannot unblock a drain stalled inside a Jetty write)
        t.start();
    }

    /** Non-blocking, latest-wins. Single-producer (tick thread) per observer. */
    public void offer(String sessionId, String payload) {
        LinkedBlockingQueue<String> slot = slots.get(sessionId);
        if (slot == null) return;
        slot.poll();        // drop any stale unsent frame
        slot.offer(payload); // install newest
    }

    private void drain(WebSocketSession session, LinkedBlockingQueue<String> slot, Runnable onTerminalFailure) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String payload = slot.take();
                if (!session.isOpen()) continue;
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(payload));
                    }
                } catch (IOException | RuntimeException e) {
                    // Any send failure is terminal for an observer: there is no admission FSM to reap
                    // it, so the failure IS its liveness signal (stricter than the bot OutboundSender's
                    // log-and-continue, and covering a persistent RuntimeException that would otherwise
                    // spin every tick). Close best-effort to unblock Jetty, then run the drain-OWNED
                    // cleanup DIRECTLY and exit — do not rely on a container close callback, which may
                    // never fire if close() itself throws (that path would otherwise leak the permit,
                    // broadcaster registration, and sender state).
                    log.warn("Observer send failed for session={}, tearing down: {}",
                            session.getId(), e.getMessage());
                    closeQuietly(session);
                    onTerminalFailure.run();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // best-effort; the handler's cleanup + detach still finish teardown
        }
    }

    public void detach(String sessionId) {
        slots.remove(sessionId);
        sessions.remove(sessionId);
        Thread t = threads.remove(sessionId);
        if (t != null) t.interrupt();
    }

    /** Test/diagnostic: the drain Thread handle (captured before detach removes it). */
    Thread threadForTest(String sessionId) {
        return threads.get(sessionId);
    }

    /** Close-first-then-interrupt to unblock any in-flight Jetty write. */
    public void detach(WebSocketSession session) {
        if (session == null) return;
        if (session.isOpen()) {
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (Exception ignored) {
                // close-on-close races are benign; interrupt below drives termination
            }
        }
        detach(session.getId());
    }

    public int attachedCount() {
        return threads.size();
    }

    /** Test/diagnostic: is a drain VT registered and alive for this session? */
    public boolean isDraining(String sessionId) {
        Thread t = threads.get(sessionId);
        return t != null && t.isAlive();
    }

    @PreDestroy
    public void shutdown() {
        // Close-first for every retained session (mirrors WorldWebSocketHandler.shutdownDetachAll):
        // a drain VT blocked inside a Jetty sendMessage will NOT exit on interrupt alone — closing
        // the transport unblocks the write. detach(WebSocketSession) closes then interrupts.
        for (WebSocketSession s : new ArrayList<>(sessions.values())) {
            detach(s);
        }
        // any residual thread whose session already went away → interrupt-only
        for (String id : new ArrayList<>(threads.keySet())) {
            detach(id);
        }
    }
}
