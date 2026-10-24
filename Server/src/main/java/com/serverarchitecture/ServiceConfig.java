package com.serverarchitecture;

public final class ServiceConfig {
    public static final String DATA_HOST = env("DATA_HOST", "localhost");
    public static final int DATA_PORT = intEnv("DATA_PORT", 9200);
    public static final long DATA_DEADLINE_MS = Long.parseLong(env("DATA_DEADLINE_MS", "3000"));
    public static final int AUTH_PORT = intEnv("AUTH_PORT", 9101);
    public static final String ACADEMIC_HOST = env("ACADEMIC_HOST", "localhost");
    public static final int ACADEMIC_PORT = intEnv("ACADEMIC_PORT", 9102);
    public static final long ACADEMIC_DEADLINE_MS = Long.parseLong(env("ACADEMIC_DEADLINE_MS", "3000"));
    public static final int ENROLLMENT_PORT = intEnv("ENROLLMENT_PORT", 9103);
    public static final boolean SEED_ON_START = Boolean.parseBoolean(env("SEED_ON_START", "true"));
    public static final boolean SEED_LOADTEST = Boolean.parseBoolean(env("SEED_LOADTEST", "false"));

    private ServiceConfig() {
    }

    public static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static int intEnv(String name, int defaultValue) {
        return Integer.parseInt(env(name, String.valueOf(defaultValue)));
    }
}
