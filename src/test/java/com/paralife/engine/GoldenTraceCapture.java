package com.paralife.engine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 19 SCALE-07 D-10: per-session SHA-256 digest accumulator.
 *
 * <p>Each call to {@link #onEmit} updates the SHA-256 digest for the named session,
 * maintaining per-session send order. {@link #digestsAsHexMap()} snapshots the
 * current digest state as a sorted {@code TreeMap<sessionId, hexString>}.
 *
 * <p>Thread-safe: all mutating methods are {@code synchronized} on {@code this}.
 * The {@link #emitCount()} accessor uses an {@link AtomicLong} to avoid locking for
 * read-only polling from the test thread.
 *
 * <p>REVIEWS L3 / H4: {@link #EMPTY_SHA256_HEX} is the SHA-256 of an empty byte array;
 * the equivalence test asserts that no per-session digest equals this value (vacuous-
 * baseline guard — confirms that the scenario actually sent frames to every session).
 */
public class GoldenTraceCapture {

    /**
     * SHA-256 of an empty byte array.
     * {@code echo -n "" | sha256sum} == {@code e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855}
     */
    public static final String EMPTY_SHA256_HEX =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final Map<String, MessageDigest> digestsBySession = new HashMap<>();
    private final AtomicLong emitCount = new AtomicLong();
    private final List<String> sessionsSeen = new ArrayList<>();

    /**
     * Accumulate {@code frameBytes} into the per-session SHA-256 digest.
     * Called from the drain-VT thread; {@code synchronized} ensures ordering is
     * correct even if multiple sessions map to the same accumulator instance.
     */
    public synchronized void onEmit(String sessionId, byte[] frameBytes) {
        MessageDigest digest = digestsBySession.computeIfAbsent(sessionId, k -> {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
            }
        });
        digest.update(frameBytes);
        emitCount.incrementAndGet();
        if (!sessionsSeen.contains(sessionId)) {
            sessionsSeen.add(sessionId);
        }
    }

    /**
     * Snapshot the current per-session digests as hex strings, sorted by sessionId.
     *
     * <p><b>Note:</b> calling {@link MessageDigest#digest()} resets each digest to its
     * initial state. This method is therefore one-shot — call it only once per run,
     * after all frames have been captured. For the equivalence test this is fine because
     * each run uses a fresh {@link GoldenTraceCapture} (or calls {@link #reset()}).
     *
     * @return sorted map of sessionId → 64-character lowercase hex SHA-256 digest
     */
    public synchronized Map<String, String> digestsAsHexMap() {
        Map<String, String> out = new TreeMap<>();
        digestsBySession.forEach((sessionId, digest) -> {
            byte[] bytes = digest.digest(); // resets digest
            out.put(sessionId, bytesToHex(bytes));
        });
        return out;
    }

    /**
     * Reset all accumulated state. After this call the capture is ready for a new run.
     */
    public synchronized void reset() {
        digestsBySession.clear();
        emitCount.set(0);
        sessionsSeen.clear();
    }

    /** Total number of frames captured across all sessions since construction or last {@link #reset()}. */
    public long emitCount() {
        return emitCount.get();
    }

    /** Ordered list of sessionIds that have been seen, in first-encounter order. */
    public synchronized List<String> sessionsSeen() {
        return new ArrayList<>(sessionsSeen);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
