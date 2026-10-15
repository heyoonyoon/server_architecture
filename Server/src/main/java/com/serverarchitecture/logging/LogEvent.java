package com.serverarchitecture.logging;

import org.slf4j.event.Level;

import java.time.Instant;

public record LogEvent(Instant time, Level level, String thread, String logger, String message, Throwable throwable) {
}
