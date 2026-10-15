package com.serverarchitecture.logging;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

public class ServerLogger extends LegacyAbstractLogger {
    private final int threshold;
    private final transient LogWriter writer;

    public ServerLogger(String name, Level threshold, LogWriter writer) {
        this.name = name;
        this.threshold = threshold.toInt();
        this.writer = writer;
    }

    @Override
    public boolean isTraceEnabled() {
        return Level.TRACE.toInt() >= threshold;
    }

    @Override
    public boolean isDebugEnabled() {
        return Level.DEBUG.toInt() >= threshold;
    }

    @Override
    public boolean isInfoEnabled() {
        return Level.INFO.toInt() >= threshold;
    }

    @Override
    public boolean isWarnEnabled() {
        return Level.WARN.toInt() >= threshold;
    }

    @Override
    public boolean isErrorEnabled() {
        return Level.ERROR.toInt() >= threshold;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                               Object[] arguments, Throwable throwable) {
        writer.append(level, name, MessageFormatter.basicArrayFormat(messagePattern, arguments), throwable);
    }
}
