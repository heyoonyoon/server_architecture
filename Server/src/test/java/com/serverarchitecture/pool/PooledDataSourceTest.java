package com.serverarchitecture.pool;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PooledDataSourceTest {
    private static final List<FakeConnection> CONNECTIONS = new CopyOnWriteArrayList<>();

    private static class FakeConnection {
        boolean autoCommit = true;
        boolean closed;
        boolean failWithConnectionError;
        int rollbacks;
        Connection connection;
    }

    @BeforeAll
    static void registerDriver() throws SQLException {
        DriverManager.registerDriver(new FakeDriver());
    }

    private static PooledDataSource dataSource(String name, int maxSize, long maxWaitMillis) {
        return new PooledDataSource("jdbc:fake:" + name, "user", "password", maxSize, maxWaitMillis);
    }

    private static FakeConnection fake(String name, int index) {
        return CONNECTIONS.stream()
                .filter(fake -> fake.connection.toString().equals(name))
                .skip(index)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void closeReturnsConnectionToPool() throws SQLException {
        PooledDataSource dataSource = dataSource("reuse", 2, 100);

        Connection first = dataSource.getConnection();
        Connection physical = first.unwrap(Connection.class);
        first.close();
        Connection second = dataSource.getConnection();

        assertTrue(first.isClosed());
        assertFalse(second.isClosed());
        assertNotSame(first, second);
        assertSame(physical, second.unwrap(Connection.class));
        assertFalse(fake("reuse", 0).closed);
        assertEquals("active=1, idle=0, waiting=0, max=2", dataSource.stats());
    }

    @Test
    void closeRollsBackOpenTransaction() throws SQLException {
        PooledDataSource dataSource = dataSource("rollback", 1, 100);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
        }

        FakeConnection fake = fake("rollback", 0);
        assertEquals(1, fake.rollbacks);
        assertTrue(fake.autoCommit);
        assertEquals("active=0, idle=1, waiting=0, max=1", dataSource.stats());
    }

    @Test
    void closedHandleRejectsUseAndDoubleCloseIsIgnored() throws SQLException {
        PooledDataSource dataSource = dataSource("closed", 1, 100);
        Connection connection = dataSource.getConnection();

        connection.close();
        connection.close();

        assertThrows(SQLException.class, connection::createStatement);
        assertFalse(connection.isValid(1));
        assertEquals("active=0, idle=1, waiting=0, max=1", dataSource.stats());
    }

    @Test
    void connectionErrorInvalidatesConnection() throws SQLException {
        PooledDataSource dataSource = dataSource("broken", 1, 100);
        Connection connection = dataSource.getConnection();
        FakeConnection fake = fake("broken", 0);
        fake.failWithConnectionError = true;

        assertThrows(SQLException.class, connection::createStatement);
        connection.close();

        assertTrue(fake.closed);
        assertEquals("active=0, idle=0, waiting=0, max=1", dataSource.stats());
        try (Connection next = dataSource.getConnection()) {
            assertNotSame(fake.connection, next.unwrap(Connection.class));
        }
    }

    @Test
    void exhaustedPoolThrowsTransientConnectionException() throws SQLException {
        PooledDataSource dataSource = dataSource("timeout", 1, 50);
        dataSource.getConnection();

        SQLTransientConnectionException e = assertThrows(SQLTransientConnectionException.class,
                dataSource::getConnection);

        assertTrue(e.getMessage().startsWith("db 풀 대기 50ms 초과"), e.getMessage());
    }

    @Test
    void failedConnectIsTransientAndFreesCapacity() throws SQLException {
        PooledDataSource dataSource = dataSource("unreachable", 1, 50);

        SQLTransientConnectionException e = assertThrows(SQLTransientConnectionException.class,
                dataSource::getConnection);

        assertEquals("08001", e.getSQLState());
        assertEquals("active=0, idle=0, waiting=0, max=1", dataSource.stats());
        assertThrows(SQLTransientConnectionException.class, dataSource::getConnection);
    }

    @Test
    void closeDataSourceClosesIdleConnections() throws SQLException {
        PooledDataSource dataSource = dataSource("shutdown", 1, 100);
        dataSource.warmUp();

        dataSource.close();

        assertTrue(fake("shutdown", 0).closed);
    }

    private static class FakeDriver implements Driver {
        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            String name = url.substring("jdbc:fake:".length());
            if (name.equals("unreachable")) {
                throw new SQLException("Connection refused", "08001");
            }
            FakeConnection fake = new FakeConnection();
            fake.connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> name;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "unwrap" -> proxy;
                        case "isClosed" -> fake.closed;
                        case "isValid" -> !fake.closed;
                        case "isReadOnly" -> false;
                        case "getAutoCommit" -> fake.autoCommit;
                        case "setAutoCommit" -> {
                            fake.autoCommit = (Boolean) args[0];
                            yield null;
                        }
                        case "rollback" -> {
                            fake.rollbacks++;
                            yield null;
                        }
                        case "close" -> {
                            fake.closed = true;
                            yield null;
                        }
                        case "createStatement" -> {
                            if (fake.failWithConnectionError) {
                                throw new SQLException("I/O error", "08006");
                            }
                            yield null;
                        }
                        default -> null;
                    });
            CONNECTIONS.add(fake);
            return fake.connection;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url.startsWith("jdbc:fake:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}
