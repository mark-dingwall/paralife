package com.paralife.engine.emergence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Attaches a Logback {@link ListAppender} to the root logger for the
 * duration of a test; exposes {@link #errorCount} and
 * {@link #emergenceMarkers}. Consumers MUST call {@link #detach} in
 * {@code @AfterEach} to avoid stickiness across test classes (threat
 * T-16-13).
 *
 * <p>Accessor methods snapshot the appender's backing list under a
 * synchronized block before streaming. The Logback {@link ListAppender}
 * appends from arbitrary logging threads (here, the tick-engine virtual
 * thread), so reading the live list from the test thread would race and
 * throw {@link java.util.ConcurrentModificationException} when long-run
 * tests sample mid-pipeline.
 */
public class TestLogCapture {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private TestLogCapture() {}

    public static TestLogCapture attach() {
        TestLogCapture c = new TestLogCapture();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        c.appender.setContext(root.getLoggerContext());
        c.appender.start();
        root.addAppender(c.appender);
        return c;
    }

    public void detach() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
    }

    private List<ILoggingEvent> snapshot() {
        synchronized (appender.list) {
            return new ArrayList<>(appender.list);
        }
    }

    public long errorCount() {
        return snapshot().stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    public List<String> emergenceMarkers() {
        return snapshot().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m != null && m.startsWith("EMERGENCE "))
                .toList();
    }
}
