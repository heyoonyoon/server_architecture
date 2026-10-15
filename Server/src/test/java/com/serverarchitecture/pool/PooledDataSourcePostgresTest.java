package com.serverarchitecture.pool;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DB_IT_URL", matches = "jdbc:postgresql:.+")
class PooledDataSourcePostgresTest {
    private static PooledDataSource dataSource;

    @BeforeAll
    static void migrate() throws SQLException {
        dataSource = new PooledDataSource(System.getenv("DB_IT_URL"), "serverarchitecture", "serverarchitecture", 5, 3000);
        dataSource.warmUp();
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void close() {
        dataSource.close();
    }

    @Test
    void flywayMigratedThroughPool() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM course_offering")) {
            resultSet.next();
            assertTrue(resultSet.getInt(1) > 0);
        }
        assertEquals("active=0, idle=5, waiting=0, max=5", dataSource.stats());
    }

    @Test
    void manyThreadsShareFiveConnections() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(40);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            futures.add(executor.submit(() -> {
                try (Connection connection = dataSource.getConnection();
                     Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT pg_backend_pid()")) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }));
        }
        List<Integer> pids = new ArrayList<>();
        for (Future<Integer> future : futures) {
            pids.add(future.get());
        }
        executor.shutdown();

        assertTrue(pids.stream().distinct().count() <= 5);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            assertTrue(resultSet.getInt(1) <= 4, "other backends=" + resultSet.getInt(1));
        }
    }

    @Test
    void uncommittedWorkIsRolledBackOnClose() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS pool_rollback_check (id int)");
            statement.execute("TRUNCATE pool_rollback_check");
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("INSERT INTO pool_rollback_check VALUES (1)");
        }

        for (int i = 0; i < 5; i++) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM pool_rollback_check")) {
                assertTrue(connection.getAutoCommit());
                resultSet.next();
                assertEquals(0, resultSet.getInt(1));
            }
        }
    }
}
