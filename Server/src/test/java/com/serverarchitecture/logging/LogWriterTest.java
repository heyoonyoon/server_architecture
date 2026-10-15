package com.serverarchitecture.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogWriterTest {
    private static final Instant TIME = Instant.parse("2026-10-15T03:04:05.006Z");

    @TempDir
    Path dir;

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final MutableClock clock = new MutableClock(TIME);

    private LogWriter writer() {
        return new LogWriter(clock, new PrintStream(console, true, StandardCharsets.UTF_8),
                new RollingLogFile(dir, "server", 30), new RollingLogFile(dir, "access", 30), "app-1", 1024, 50);
    }

    @Test
    void routesAccessAndServerLogs() throws IOException {
        LogWriter writer = writer();
        ServerLoggerFactory factory = new ServerLoggerFactory(Level.INFO, writer);

        factory.getLogger("com.serverarchitecture.Server").info("서버 포트 {}", 8081);
        factory.getLogger("access").info("user=S001 | command=ENROLL");
        writer.close();

        String thread = Thread.currentThread().getName();
        assertEquals(List.of("2026-10-15 12:04:05.006 INFO  [" + thread + "] com.serverarchitecture.Server - 서버 포트 8081"),
                Files.readAllLines(dir.resolve("server.log")));
        assertEquals(List.of("2026-10-15 12:04:05.006 | server=app-1 | user=S001 | command=ENROLL"),
                Files.readAllLines(dir.resolve("access.log")));
        assertEquals("2026-10-15 12:04:05.006 INFO  [" + thread + "] com.serverarchitecture.Server - 서버 포트 8081"
                        + System.lineSeparator()
                        + "2026-10-15 12:04:05.006 INFO  [" + thread + "] access - user=S001 | command=ENROLL"
                        + System.lineSeparator(),
                console.toString(StandardCharsets.UTF_8));
    }

    @Test
    void filtersBelowThreshold() throws IOException {
        LogWriter writer = writer();
        Logger logger = new ServerLoggerFactory(Level.INFO, writer).getLogger("x");

        assertFalse(logger.isDebugEnabled());
        logger.debug("hidden {}", 1);
        logger.warn("shown {} {}", 1, 2, new IllegalStateException("boom"));
        writer.close();

        List<String> lines = Files.readAllLines(dir.resolve("server.log"));
        assertTrue(lines.get(0).endsWith("WARN  [" + Thread.currentThread().getName() + "] x - shown 1 2"));
        assertEquals("java.lang.IllegalStateException: boom", lines.get(1));
    }

    @Test
    void accessLoggerStaysAtInfoWhenRootIsWarn() {
        ServerLoggerFactory factory = new ServerLoggerFactory(Level.WARN, null);

        assertTrue(factory.getLogger("access").isInfoEnabled());
        assertFalse(factory.getLogger("x").isInfoEnabled());
    }

    @Test
    void rollsOverAtSeoulMidnight() throws IOException {
        LogWriter writer = writer();
        Logger logger = new ServerLoggerFactory(Level.INFO, writer).getLogger("x");

        clock.set(Instant.parse("2026-10-15T14:59:59.999Z"));
        logger.info("before");
        clock.set(Instant.parse("2026-10-15T15:00:00.000Z"));
        logger.info("after");
        writer.close();

        List<String> archived = Files.readAllLines(dir.resolve("server.2026-10-15.log"));
        List<String> active = Files.readAllLines(dir.resolve("server.log"));
        assertEquals(1, archived.size());
        assertTrue(archived.get(0).startsWith("2026-10-15 23:59:59.999") && archived.get(0).endsWith("before"));
        assertEquals(1, active.size());
        assertTrue(active.get(0).startsWith("2026-10-16 00:00:00.000") && active.get(0).endsWith("after"));
    }

    @Test
    void rollsOverStaleActiveFileOnFirstWrite() throws IOException {
        Path active = dir.resolve("access.log");
        Files.writeString(active, "old" + System.lineSeparator());
        Files.setLastModifiedTime(active, FileTime.from(Instant.parse("2026-10-13T10:00:00Z")));
        LogWriter writer = writer();

        writer.append(Level.INFO, "access", "new", null);
        writer.close();

        assertEquals(List.of("old"), Files.readAllLines(dir.resolve("access.2026-10-13.log")));
        assertEquals(1, Files.readAllLines(active).size());
    }

    @Test
    void deletesArchivesOlderThanThirtyDays() throws IOException {
        RollingLogFile file = new RollingLogFile(dir, "server", 30);
        LocalDate today = LocalDate.of(2026, 10, 16);
        for (LocalDate date : List.of(today.minusDays(40), today.minusDays(31), today.minusDays(30), today.minusDays(2))) {
            Files.writeString(file.archiveFile(date), "x");
        }
        Files.writeString(dir.resolve("other.2026-01-01.log"), "x");

        file.write(Instant.parse("2026-10-15T03:00:00Z"), "a" + System.lineSeparator());
        file.write(Instant.parse("2026-10-15T15:00:00Z"), "b" + System.lineSeparator());
        file.close();

        assertFalse(Files.exists(file.archiveFile(today.minusDays(40))));
        assertFalse(Files.exists(file.archiveFile(today.minusDays(31))));
        assertTrue(Files.exists(file.archiveFile(today.minusDays(30))));
        assertTrue(Files.exists(file.archiveFile(today.minusDays(2))));
        assertTrue(Files.exists(file.archiveFile(today.minusDays(1))));
        assertTrue(Files.exists(dir.resolve("other.2026-01-01.log")));
    }

    @Test
    void drainsQueueOnClose() throws Exception {
        LogWriter writer = new LogWriter(clock, new PrintStream(console, true, StandardCharsets.UTF_8),
                new RollingLogFile(dir, "server", 30), new RollingLogFile(dir, "access", 30), "app-1", 200_000, 1000);
        Logger logger = new ServerLoggerFactory(Level.INFO, writer).getLogger("x");
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            int id = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    logger.info("t{} n{}", id, i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        writer.close();
        logger.info("after close");

        assertEquals(0, writer.dropped());
        assertEquals(80_001, Files.readAllLines(dir.resolve("server.log")).size());
    }
}
