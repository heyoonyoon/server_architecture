package com.serverarchitecture.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerLoggerFactory implements ILoggerFactory {
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private final Level rootLevel;
    private final LogWriter writer;

    public ServerLoggerFactory(Level rootLevel, LogWriter writer) {
        this.rootLevel = rootLevel;
        this.writer = writer;
    }

    @Override
    public Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, key -> new ServerLogger(key,
                key.equals(LogWriter.ACCESS_LOGGER) ? Level.INFO : rootLevel, writer));
    }
}
