package com.serverarchitecture;

import com.serverarchitecture.pool.PooledDataSource;

import java.sql.SQLException;

public class Database {
    public static PooledDataSource createDataSource() {
        PooledDataSource dataSource = new PooledDataSource(
                getEnv("DB_URL", "jdbc:postgresql://localhost:5432/serverarchitecture"),
                getEnv("DB_USER", "serverarchitecture"),
                getEnv("DB_PASSWORD", "serverarchitecture"),
                Integer.parseInt(getEnv("DB_POOL_MAX", "30")),
                Long.parseLong(getEnv("DB_POOL_TIMEOUT_MS", "3000")));
        try {
            dataSource.warmUp();
        } catch (SQLException e) {
            dataSource.close();
            throw new IllegalStateException(e.getMessage(), e);
        }
        return dataSource;
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
