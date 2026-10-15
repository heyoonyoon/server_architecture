package com.serverarchitecture.redis;

import com.serverarchitecture.pool.PoolTimeoutException;
import com.serverarchitecture.pool.ResourceFactory;
import com.serverarchitecture.pool.ResourcePool;

public class RedisPool implements AutoCloseable {
    private static final int TIMEOUT_MS = 2000;
    private static final long VALIDATE_AFTER_IDLE_MS = 30000;

    private final ResourcePool<RedisConnection> pool;

    public RedisPool(String host, int port, int maxSize, int minIdle, long maxWaitMillis) {
        this.pool = new ResourcePool<>("redis", new ConnectionFactory(host, port),
                maxSize, minIdle, maxWaitMillis, VALIDATE_AFTER_IDLE_MS);
    }

    public boolean warmUp() {
        try {
            pool.warmUp();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public RedisConnection getResource() {
        RedisConnection connection;
        try {
            connection = pool.borrow();
        } catch (PoolTimeoutException e) {
            throw new RedisPoolExhaustedException(e.getMessage(), e);
        } catch (RedisException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisConnectionException("Redis 연결 대기 중 인터럽트되었습니다.", e);
        } catch (Exception e) {
            throw new RedisConnectionException(e.getMessage(), e);
        }
        connection.lease(this);
        return connection;
    }

    public String stats() {
        return pool.stats();
    }

    @Override
    public void close() {
        pool.close();
    }

    void giveBack(RedisConnection connection) {
        if (connection.isBroken() || connection.hasPendingReplies()) {
            pool.invalidate(connection);
        } else {
            pool.release(connection);
        }
    }

    private record ConnectionFactory(String host, int port) implements ResourceFactory<RedisConnection> {
        @Override
        public RedisConnection create() {
            return RedisConnection.connect(host, port, TIMEOUT_MS);
        }

        @Override
        public boolean validate(RedisConnection connection) {
            try {
                return "PONG".equals(connection.ping());
            } catch (RedisException e) {
                return false;
            }
        }

        @Override
        public void destroy(RedisConnection connection) {
            connection.disconnect();
        }
    }
}
