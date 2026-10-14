package com.serverarchitecture;

import java.time.ZoneId;

public final class EnrollmentConfig {
    public static final String SEMESTER = env("SEMESTER", "2026-2");
    public static final String REDIS_HOST = env("REDIS_HOST", "localhost");
    public static final int REDIS_PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    public static final int REDIS_POOL_MAX = Integer.parseInt(env("REDIS_POOL_MAX", "128"));
    public static final int REDIS_POOL_MIN_IDLE = Integer.parseInt(env("REDIS_POOL_MIN_IDLE", "8"));
    public static final long REDIS_POOL_MAX_WAIT_MS = Long.parseLong(env("REDIS_POOL_MAX_WAIT_MS", "2000"));
    public static final int CATALOG_REFRESH_SECONDS = Integer.parseInt(env("CATALOG_REFRESH_SECONDS", "60"));
    public static final String KAFKA_BOOTSTRAP = env("KAFKA_BOOTSTRAP", "localhost:9092");
    public static final String TOPIC = "enrollment-events";
    public static final String DEAD_LETTER_TOPIC = "enrollment-events-dlt";
    public static final int QUEUE_MAX_ACTIVE = Integer.parseInt(env("QUEUE_MAX_ACTIVE", "50"));
    public static final int PASS_TTL_SECONDS = Integer.parseInt(env("PASS_TTL_SECONDS", "300"));
    public static final int PASS_CLAIM_SECONDS = Integer.parseInt(env("PASS_CLAIM_SECONDS", "30"));
    public static final int WAITER_TIMEOUT_SECONDS = Integer.parseInt(env("WAITER_TIMEOUT_SECONDS", "30"));
    public static final int POLL_INTERVAL_MS = Integer.parseInt(env("POLL_INTERVAL_MS", "2000"));
    public static final int WORKER_TICK_MS = Integer.parseInt(env("WORKER_TICK_MS", "500"));

    public static final String QUEUE_KEY = "queue:" + SEMESTER;
    public static final String ACTIVE_KEY = "active:" + SEMESTER;
    public static final String SEATS_KEY = "seats:" + SEMESTER;
    public static final String READY_KEY = "ready:" + SEMESTER;
    public static final String SEEN_KEY = "seen:" + SEMESTER;
    public static final String OPEN_KEY = "open:" + SEMESTER;
    public static final String MAX_KEY = "max:" + SEMESTER;
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private EnrollmentConfig() {
    }

    public static String studentOfferingsKey(String studentId) {
        return "student:offerings:" + SEMESTER + ":" + studentId;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
