package com.serverarchitecture;

public final class EnrollmentConfig {
    public static final String SEMESTER = env("SEMESTER", "2026-2");
    public static final String REDIS_HOST = env("REDIS_HOST", "localhost");
    public static final int REDIS_PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    public static final String KAFKA_BOOTSTRAP = env("KAFKA_BOOTSTRAP", "localhost:9092");
    public static final String TOPIC = "enrollment-events";
    public static final String DEAD_LETTER_TOPIC = "enrollment-events-dlt";
    public static final int QUEUE_MAX_ACTIVE = Integer.parseInt(env("QUEUE_MAX_ACTIVE", "50"));
    public static final int PASS_TTL_SECONDS = Integer.parseInt(env("PASS_TTL_SECONDS", "300"));
    public static final int POLL_INTERVAL_MS = Integer.parseInt(env("POLL_INTERVAL_MS", "2000"));
    public static final int WORKER_TICK_MS = Integer.parseInt(env("WORKER_TICK_MS", "500"));

    public static final String QUEUE_KEY = "queue:" + SEMESTER;
    public static final String ACTIVE_KEY = "active:" + SEMESTER;
    public static final String SEATS_KEY = "seats:" + SEMESTER;
    public static final String READY_KEY = "ready:" + SEMESTER;

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
