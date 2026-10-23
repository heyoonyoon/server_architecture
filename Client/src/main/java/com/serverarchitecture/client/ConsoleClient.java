package com.serverarchitecture.client;

public class ConsoleClient {
    public static void main(String[] args) {
        String host = env("SERVER_HOST", "localhost");
        int port = Integer.parseInt(env("SERVER_PORT", "8080"));
        throw new UnsupportedOperationException("ConsoleClient " + host + ":" + port);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
