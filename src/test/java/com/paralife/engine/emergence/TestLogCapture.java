package com.paralife.engine.emergence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Attaches a Logback {@link ListAppender} to the root logger for the
 * duration of a test; exposes {@link #errorCount} and
 * {@link #emergenceMarkers}. Consumers MUST call {@link #detach} in
 * {@code @AfterEach} to avoid stickiness across test classes (threat
 * T-16-13).
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

    public long errorCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    public List<String> emergenceMarkers() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m != null && m.startsWith("EMERGENCE "))
                .toList();
    }
}
