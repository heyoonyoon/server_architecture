package com.serverarchitecture;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class Database {
    public static HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(getEnv("DB_URL", "jdbc:postgresql://localhost:5432/serverarchitecture"));
        config.setUsername(getEnv("DB_USER", "serverarchitecture"));
        config.setPassword(getEnv("DB_PASSWORD", "serverarchitecture"));
        return new HikariDataSource(config);
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
