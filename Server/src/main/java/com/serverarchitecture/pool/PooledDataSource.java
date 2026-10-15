package com.serverarchitecture.pool;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.util.logging.Logger;

public class PooledDataSource implements DataSource, AutoCloseable {
    private static final long VALIDATE_AFTER_IDLE_MS = 500;
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    private final ResourcePool<Connection> pool;
    private volatile PrintWriter logWriter;
    private volatile int loginTimeout;

    public PooledDataSource(String url, String user, String password, int maxSize, long maxWaitMillis) {
        this.pool = new ResourcePool<>("db", new ConnectionFactory(url, user, password),
                maxSize, maxSize, maxWaitMillis, VALIDATE_AFTER_IDLE_MS);
    }

    public void warmUp() throws SQLException {
        try {
            pool.warmUp();
        } catch (Exception e) {
            throw translate(e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection;
        try {
            connection = pool.borrow();
        } catch (SQLException e) {
            throw new SQLTransientConnectionException("db 연결 생성 실패: " + e.getMessage(), e.getSQLState(), e);
        } catch (Exception e) {
            throw translate(e);
        }
        return (Connection) Proxy.newProxyInstance(PooledDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new PooledConnection(connection));
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("풀 데이터소스는 사용자별 연결을 지원하지 않습니다.");
    }

    public String stats() {
        return pool.stats();
    }

    @Override
    public void close() {
        pool.close();
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Wrapper 대상이 아닙니다: " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    private static SQLException translate(Exception e) {
        if (e instanceof SQLException sqlException) {
            return sqlException;
        }
        if (e instanceof PoolTimeoutException) {
            return new SQLTransientConnectionException(e.getMessage(), null, e);
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new SQLException(e.getMessage(), e);
    }

    private record ConnectionFactory(String url, String user, String password) implements ResourceFactory<Connection> {
        @Override
        public Connection create() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public boolean validate(Connection connection) {
            try {
                return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
            } catch (SQLException e) {
                return false;
            }
        }

        @Override
        public void destroy(Connection connection) throws SQLException {
            connection.close();
        }
    }

    private class PooledConnection implements InvocationHandler {
        private final Connection connection;
        private boolean closed;
        private boolean broken;

        PooledConnection(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close":
                    giveBack();
                    return null;
                case "isClosed":
                    return closed;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Pooled" + connection;
                default:
                    break;
            }
            if (closed) {
                if (method.getName().equals("isValid")) {
                    return false;
                }
                throw new SQLException("연결이 이미 닫혔습니다.", "08003");
            }
            try {
                return method.invoke(connection, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SQLException sqlException && isConnectionError(sqlException)) {
                    broken = true;
                }
                throw cause;
            }
        }

        private boolean isConnectionError(SQLException e) {
            String state = e.getSQLState();
            return state != null && state.startsWith("08");
        }

        private void giveBack() {
            if (closed) {
                return;
            }
            closed = true;
            if (broken) {
                pool.invalidate(connection);
                return;
            }
            try {
                if (connection.isClosed()) {
                    pool.invalidate(connection);
                    return;
                }
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                if (connection.isReadOnly()) {
                    connection.setReadOnly(false);
                }
                connection.clearWarnings();
            } catch (SQLException e) {
                pool.invalidate(connection);
                return;
            }
            pool.release(connection);
        }
    }
}
