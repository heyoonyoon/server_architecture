package com.serverarchitecture.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class LogFormat {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZONE);
    private static final String NEWLINE = System.lineSeparator();

    private LogFormat() {
    }

    public static String standard(LogEvent event, String abbreviatedLogger) {
        StringBuilder line = new StringBuilder(128)
                .append(TIMESTAMP.format(event.time())).append(' ')
                .append(pad(event.level().name())).append(" [")
                .append(event.thread()).append("] ")
                .append(abbreviatedLogger).append(" - ")
                .append(event.message()).append(NEWLINE);
        return appendThrowable(line, event.throwable());
    }

    public static String access(LogEvent event, String hostname) {
        StringBuilder line = new StringBuilder(128)
                .append(TIMESTAMP.format(event.time()))
                .append(" | server=").append(hostname)
                .append(" | ").append(event.message()).append(NEWLINE);
        return appendThrowable(line, event.throwable());
    }

    public static String abbreviate(String name, int targetLength) {
        if (name.length() <= targetLength) {
            return name;
        }
        String[] segments = name.split("\\.", -1);
        if (segments.length == 1) {
            return name;
        }
        int toTrim = name.length() - targetLength;
        StringBuilder result = new StringBuilder(targetLength);
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            if (toTrim > 0 && segment.length() > 1) {
                toTrim -= segment.length() - 1;
                result.append(segment.charAt(0));
            } else {
                result.append(segment);
            }
            result.append('.');
        }
        return result.append(segments[segments.length - 1]).toString();
    }

    private static String pad(String level) {
        return level.length() >= 5 ? level : level + " ".repeat(5 - level.length());
    }

    private static String appendThrowable(StringBuilder line, Throwable throwable) {
        if (throwable != null) {
            StringWriter trace = new StringWriter();
            throwable.printStackTrace(new PrintWriter(trace));
            line.append(trace);
        }
        return line.toString();
    }
}
