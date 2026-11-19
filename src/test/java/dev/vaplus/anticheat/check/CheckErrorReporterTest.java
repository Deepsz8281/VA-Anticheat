package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.testutil.ManualTimeSource;
import dev.vaplus.anticheat.testutil.TestContexts.CapturingHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CheckErrorReporter")
class CheckErrorReporterTest {

    private ManualTimeSource time;
    private CapturingHandler handler;
    private CheckErrorReporter reporter;

    @BeforeEach
    void setUp() {
        time = new ManualTimeSource();
        handler = new CapturingHandler();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        reporter = new CheckErrorReporter(logger, time);
    }

    @Test
    @DisplayName("logs the first error with source, player and cause")
    void firstErrorLogged() {
        IllegalStateException cause = new IllegalStateException("x");
        reporter.report("fly", "Steve", cause);
        assertEquals(1, handler.records().size());
        LogRecord record = handler.records().get(0);
        assertEquals(Level.WARNING, record.getLevel());
        assertTrue(record.getMessage().contains("fly"));
        assertTrue(record.getMessage().contains("Steve"));
        assertSame(cause, record.getThrown());
    }

    @Test
    @DisplayName("repeats within 60 seconds are counted but not logged")
    void suppressed() {
        for (int i = 0; i < 100; i++) {
            reporter.report("fly", "Steve", new RuntimeException());
            time.advanceMillis(100);
        }
        assertEquals(1, handler.records().size());
        assertEquals(100, reporter.totalErrors());
    }

    @Test
    @DisplayName("the window is exactly 60 seconds")
    void windowBoundary() {
        reporter.report("fly", "Steve", new RuntimeException());
        time.advanceMillis(59_999);
        reporter.report("fly", "Steve", new RuntimeException());
        assertEquals(1, handler.records().size());
        time.advanceMillis(1);
        reporter.report("fly", "Steve", new RuntimeException());
        assertEquals(2, handler.records().size());
    }

    @Test
    @DisplayName("sources are rate-limited independently")
    void independentSources() {
        reporter.report("fly", "Steve", new RuntimeException());
        reporter.report("speed", "Steve", new RuntimeException());
        reporter.report("fly", "Alex", new RuntimeException());
        assertEquals(2, handler.records().size(), "same source is suppressed regardless of player");
        assertEquals(3, reporter.totalErrors());
    }

    @Test
    @DisplayName("works even when the wall clock starts at 0 or negative")
    void clockEdge() {
        time.setCurrentTimeMillis(0);
        reporter.report("fly", "Steve", new RuntimeException());
        reporter.report("fly", "Steve", new RuntimeException());
        assertEquals(1, handler.records().size());
        time.setCurrentTimeMillis(-1_000_000);
        reporter.report("speed", "Steve", new RuntimeException());
        assertEquals(2, handler.records().size());
    }
}
