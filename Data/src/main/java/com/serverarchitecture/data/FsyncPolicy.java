package com.serverarchitecture.data;

import java.util.Locale;

public enum FsyncPolicy {
    ALWAYS,
    BATCH,
    NEVER;

    public static FsyncPolicy parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DATA_FSYNC must be always, batch or never");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "always" -> ALWAYS;
            case "batch" -> BATCH;
            case "never" -> NEVER;
            default -> throw new IllegalArgumentException("DATA_FSYNC must be always, batch or never: " + value);
        };
    }
}
