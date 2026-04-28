package com.paralife.admission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AttributionTagger} — covering null-session handling,
 * source/harness tag extraction, formatLogFields, cardinality cap, overflow folding,
 * warn-once at fold site (Round 2 Codex HIGH), and synchronized slot-claim
 * (Round 2 Claude+Codex MEDIUM).
 */
class AttributionTaggerTest {

    private AttributionTagger tagger;

    @BeforeEach
    void setup() {
        // cap=64, null tickEngine (tick=-1 in warn-once log)
        tagger = new AttributionTagger(64, null);
    }

    // ── Null session ─────────────────────────────────────────────────────────

    @Test
    void tagsForNullSessionReturnsSourceUnknown() {
        Tags tags = tagger.tagsFor(null);
        assertThat(tags).isEqualTo(Tags.of("source", "unknown"));
    }

    @Test
    void formatLogFieldsNullSessionReturnsSourceUnknown() {
        assertThat(AttributionTagger.formatLogFields(null)).isEqualTo("source=unknown");
    }

    // ── Harness session ──────────────────────────────────────────────────────

    @Test
    void tagsForHarnessSessionReturnsTwoTags() {
        FakeSession session = new FakeSession();
        session.attrs().put(AttributionTagger.ATTR_SOURCE, "harness");
        session.attrs().put(AttributionTagger.ATTR_HARNESS, "harness-A");

        Tags tags = tagger.tagsFor(session);
        assertThat(tags).isEqualTo(Tags.of("source", "harness", "harness", "harness-A"));
    }

    @Test
    void formatLogFieldsHarnessSession() {
        FakeSession session = new FakeSession();
        session.attrs().put(AttributionTagger.ATTR_SOURCE, "harness");
        session.attrs().put(AttributionTagger.ATTR_HARNESS, "harness-A");

        assertThat(AttributionTagger.formatLogFields(session))
                .isEqualTo("source=harness harness=harness-A");
    }

    // ── Operator session (source only, no harness tag) ───────────────────────

    @Test
    void tagsForOperatorSessionReturnsSourceOnly() {
        FakeSession session = new FakeSession();
        session.attrs().put(AttributionTagger.ATTR_SOURCE, "operator");

        Tags tags = tagger.tagsFor(session);
        assertThat(tags).isEqualTo(Tags.of("source", "operator"));
    }

    @Test
    void formatLogFieldsOperatorSession() {
        FakeSession session = new FakeSession();
        session.attrs().put(AttributionTagger.ATTR_SOURCE, "operator");

        assertThat(AttributionTagger.formatLogFields(session)).isEqualTo("source=operator");
    }

    // ── AttributionConfig defaults and validation ────────────────────────────

    @Test
    void maxHarnessCardinalityDefaultIs64() {
        AdmissionConfig cfg = AdmissionConfig.defaults();
        assertThat(cfg.attribution().maxHarnessCardinality()).isEqualTo(64);
    }

    @Test
    void attributionConfigRejectsNonPositiveCardinality() {
        assertThatThrownBy(() -> new AdmissionConfig.AttributionConfig(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionConfig.AttributionConfig(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Overflow folding ─────────────────────────────────────────────────────

    @Test
    void overflowFoldingAt65thUniqueId() {
        // Fill up to cap=64
        AttributionTagger cappedTagger = new AttributionTagger(64, null);
        for (int i = 1; i <= 64; i++) {
            FakeSession s = harnessSession("h-" + i);
            Tags tags = cappedTagger.tagsFor(s);
            assertThat(tags.stream().filter(t -> t.getKey().equals("harness"))
                    .map(io.micrometer.core.instrument.Tag::getValue)
                    .findFirst().orElse("MISSING"))
                    .isEqualTo("h-" + i);
        }
        // 65th should overflow
        FakeSession overflow = harnessSession("h-65");
        Tags tags = cappedTagger.tagsFor(overflow);
        assertThat(tags.stream().filter(t -> t.getKey().equals("harness"))
                .map(io.micrometer.core.instrument.Tag::getValue)
                .findFirst().orElse("MISSING"))
                .isEqualTo("overflow");
    }

    @Test
    void tagsForIsIdempotentForAlreadyObservedIds() {
        FakeSession session = harnessSession("h-1");
        Tags first = tagger.tagsFor(session);
        Tags second = tagger.tagsFor(session);
        assertThat(first).isEqualTo(second);
        // should still be h-1, not overflow
        assertThat(first.stream().filter(t -> t.getKey().equals("harness"))
                .map(io.micrometer.core.instrument.Tag::getValue)
                .findFirst().orElse("MISSING"))
                .isEqualTo("h-1");
    }

    // ── Warn-once at fold site (Round 2 Codex HIGH) ──────────────────────────

    @Test
    void warnOnceLogEmittedAtFoldSiteWithRawId() {
        // cap=4, trigger overflow on 5th unique id "h-5"
        AttributionTagger small = new AttributionTagger(4, null);

        Logger logger = (Logger) LoggerFactory.getLogger(AttributionTagger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);

        try {
            // Fill cap
            for (int i = 1; i <= 4; i++) {
                small.tagsFor(harnessSession("h-" + i));
            }
            // 5th → overflow; warn-once should fire here
            small.tagsFor(harnessSession("h-5"));
            // 6th, 7th → also overflow; must NOT emit additional log lines
            small.tagsFor(harnessSession("h-6"));
            small.tagsFor(harnessSession("h-7"));

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getMessage().contains("HARNESS overflow first-seen"))
                    .toList();

            assertThat(warnings).hasSize(1);
            // The raw id h-5 must appear, NOT "overflow"
            String formatted = warnings.get(0).getFormattedMessage();
            assertThat(formatted).contains("h-5");
            assertThat(formatted).doesNotContain("=overflow");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void warnOnceLogContainsTickField() {
        AttributionTagger small = new AttributionTagger(1, null);

        Logger logger = (Logger) LoggerFactory.getLogger(AttributionTagger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);

        try {
            small.tagsFor(harnessSession("h-1"));   // cap=1: already full
            small.tagsFor(harnessSession("h-2"));   // overflow → warn

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getMessage().contains("HARNESS overflow first-seen"))
                    .toList();

            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage()).contains("tick=");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ── Synchronized slot-claim (Round 2 Claude+Codex MEDIUM) ────────────────

    @Test
    void synchronizedSlotClaimEliminatesCapBoundaryRace() throws InterruptedException {
        int cap = 8;
        int threads = 100;
        AttributionTagger raceTagger = new AttributionTagger(cap, null);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        CopyOnWriteArrayList<Tags> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int id = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Tags tags = raceTagger.tagsFor(harnessSession("h-" + id));
                results.add(tags);
                done.countDown();
            });
        }

        start.countDown();
        done.await();

        long overflowCount = results.stream()
                .filter(t -> t.stream().anyMatch(tag ->
                        tag.getKey().equals("harness") && tag.getValue().equals("overflow")))
                .count();
        long normalCount = results.stream()
                .filter(t -> t.stream().anyMatch(tag ->
                        tag.getKey().equals("harness") && !tag.getValue().equals("overflow")))
                .count();

        // Exactly cap unique harnesses should be registered; rest overflow
        assertThat(normalCount).isEqualTo(cap);
        assertThat(overflowCount).isEqualTo(threads - cap);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static FakeSession harnessSession(String harnessId) {
        FakeSession s = new FakeSession();
        s.attrs().put(AttributionTagger.ATTR_SOURCE, "harness");
        s.attrs().put(AttributionTagger.ATTR_HARNESS, harnessId);
        return s;
    }

    /**
     * Minimal WebSocketSession stub for test use.
     */
    static class FakeSession implements WebSocketSession {
        private final Map<String, Object> attrs = new HashMap<>();

        Map<String, Object> attrs() { return attrs; }

        @Override public String getId() { return "test-" + System.identityHashCode(this); }
        @Override public URI getUri() { return null; }
        @Override public org.springframework.http.HttpHeaders getHandshakeHeaders() { return null; }
        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int maximumMessageSize) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int maximumMessageSize) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public void sendMessage(WebSocketMessage<?> message) {}
        @Override public boolean isOpen() { return true; }
        @Override public void close() {}
        @Override public void close(CloseStatus status) {}
    }
}
