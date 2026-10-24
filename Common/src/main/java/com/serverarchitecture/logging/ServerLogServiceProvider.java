package com.serverarchitecture.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;

public class ServerLogServiceProvider implements SLF4JServiceProvider {
    private static final int MAX_HISTORY_DAYS = 30;
    private static final int QUEUE_CAPACITY = 16384;
    private static final long OFFER_TIMEOUT_MILLIS = 50;

    private ILoggerFactory loggerFactory;
    private IMarkerFactory markerFactory;
    private MDCAdapter mdcAdapter;

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        Path logDir = Path.of(setting("LOG_DIR", "/app/logs"));
        LogWriter writer = new LogWriter(Clock.systemUTC(), System.out,
                new RollingLogFile(logDir, "server", MAX_HISTORY_DAYS),
                new RollingLogFile(logDir, "access", MAX_HISTORY_DAYS),
                hostname(), QUEUE_CAPACITY, OFFER_TIMEOUT_MILLIS);
        Runtime.getRuntime().addShutdownHook(new Thread(writer::close, "log-writer-shutdown"));
        loggerFactory = new ServerLoggerFactory(level(setting("LOG_LEVEL", "INFO")), writer);
        markerFactory = new BasicMarkerFactory();
        mdcAdapter = new BasicMDCAdapter();
    }

    private static String setting(String key, String defaultValue) {
        String value = System.getProperty(key, System.getenv(key));
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Level level(String value) {
        try {
            return Level.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Level.INFO;
        }
    }

    private static String hostname() {
        String value = System.getenv("HOSTNAME");
        if (value != null && !value.isBlank()) {
            return value;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
