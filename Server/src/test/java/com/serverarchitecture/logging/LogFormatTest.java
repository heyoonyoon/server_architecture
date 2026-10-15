package com.serverarchitecture.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFormatTest {
    private static final Instant TIME = Instant.parse("2026-10-15T03:04:05.006Z");
    private static final String NEWLINE = System.lineSeparator();

    @Test
    void formatsStandardLineInSeoulTime() {
        LogEvent event = new LogEvent(TIME, Level.INFO, "main", "com.serverarchitecture.Server", "started 8081", null);

        assertEquals("2026-10-15 12:04:05.006 INFO  [main] com.serverarchitecture.Server - started 8081" + NEWLINE,
                LogFormat.standard(event, "com.serverarchitecture.Server"));
    }

    @Test
    void formatsAccessLineWithHostname() {
        LogEvent event = new LogEvent(TIME, Level.INFO, "grpc-default-executor-0", "access", "user=S001", null);

        assertEquals("2026-10-15 12:04:05.006 | server=app-1 | user=S001" + NEWLINE, LogFormat.access(event, "app-1"));
    }

    @Test
    void appendsStackTrace() {
        LogEvent event = new LogEvent(TIME, Level.ERROR, "main", "x", "failed", new IllegalStateException("boom"));

        String line = LogFormat.standard(event, "x");

        assertTrue(line.startsWith("2026-10-15 12:04:05.006 ERROR [main] x - failed" + NEWLINE
                + "java.lang.IllegalStateException: boom" + NEWLINE + "\tat "));
    }

    @Test
    void abbreviatesLoggerNameLikeLogback() {
        assertEquals("com.serverarchitecture.Server", LogFormat.abbreviate("com.serverarchitecture.Server", 36));
        assertEquals("o.a.k.c.producer.internals.Sender",
                LogFormat.abbreviate("org.apache.kafka.clients.producer.internals.Sender", 36));
        assertEquals("m.s.s.Bar", LogFormat.abbreviate("mainPackage.sub.sample.Bar", 5));
        assertEquals("AVeryLongClassNameWithoutAnyPackageAtAll",
                LogFormat.abbreviate("AVeryLongClassNameWithoutAnyPackageAtAll", 36));
    }
}
