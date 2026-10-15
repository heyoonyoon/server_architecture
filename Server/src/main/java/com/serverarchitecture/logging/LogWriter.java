package com.serverarchitecture.logging;

import org.slf4j.event.Level;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LogWriter implements AutoCloseable {
    public static final String ACCESS_LOGGER = "access";
    private static final int LOGGER_LENGTH = 36;
    private static final int BATCH_SIZE = 1024;

    private final Clock clock;
    private final PrintStream console;
    private final RollingLogFile serverFile;
    private final RollingLogFile accessFile;
    private final String hostname;
    private final BlockingQueue<LogEvent> queue;
    private final long offerTimeoutMillis;
    private final AtomicLong dropped = new AtomicLong();
    private final Map<String, String> abbreviations = new HashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    public LogWriter(Clock clock, PrintStream console, RollingLogFile serverFile, RollingLogFile accessFile,
                     String hostname, int capacity, long offerTimeoutMillis) {
        this.clock = clock;
        this.console = console;
        this.serverFile = serverFile;
        this.accessFile = accessFile;
        this.hostname = hostname;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.offerTimeoutMillis = offerTimeoutMillis;
        this.thread = new Thread(this::run, "log-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void append(Level level, String logger, String message, Throwable throwable) {
        LogEvent event = new LogEvent(clock.instant(), level, Thread.currentThread().getName(), logger, message, throwable);
        if (!running) {
            write(List.of(event));
            return;
        }
        try {
            if (!queue.offer(event, offerTimeoutMillis, TimeUnit.MILLISECONDS)) {
                dropped.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!queue.offer(event)) {
                dropped.incrementAndGet();
            }
        }
        if (!running) {
            drain();
        }
    }

    public long dropped() {
        return dropped.get();
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drain();
        synchronized (this) {
            serverFile.close();
            accessFile.close();
        }
    }

    private void run() {
        List<LogEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                LogEvent first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                write(batch);
                batch.clear();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                batch.clear();
                e.printStackTrace();
            }
        }
    }

    private synchronized void drain() {
        List<LogEvent> rest = new ArrayList<>();
        queue.drainTo(rest);
        write(rest);
    }

    private synchronized void write(List<LogEvent> events) {
        long lost = dropped.getAndSet(0);
        if (lost > 0) {
            events = new ArrayList<>(events);
            events.add(new LogEvent(clock.instant(), Level.WARN, Thread.currentThread().getName(),
                    LogWriter.class.getName(), "Dropped " + lost + " log events because the log queue was full", null));
        }
        if (events.isEmpty()) {
            return;
        }
        StringBuilder consoleText = new StringBuilder();
        for (LogEvent event : events) {
            String line = LogFormat.standard(event,
                    abbreviations.computeIfAbsent(event.logger(), name -> LogFormat.abbreviate(name, LOGGER_LENGTH)));
            consoleText.append(line);
            if (isAccess(event.logger())) {
                accessFile.write(event.time(), LogFormat.access(event, hostname));
            } else {
                serverFile.write(event.time(), line);
            }
        }
        byte[] bytes = consoleText.toString().getBytes(StandardCharsets.UTF_8);
        console.write(bytes, 0, bytes.length);
        console.flush();
        serverFile.flush();
        accessFile.flush();
    }

    private static boolean isAccess(String logger) {
        return logger.equals(ACCESS_LOGGER) || logger.startsWith(ACCESS_LOGGER + ".");
    }
}
