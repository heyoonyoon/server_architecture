package com.serverarchitecture.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ResourcePool<T> implements AutoCloseable {
    private final String name;
    private final ResourceFactory<T> factory;
    private final int maxSize;
    private final int minIdle;
    private final long maxWaitMillis;
    private final long validateAfterIdleNanos;
    private final Semaphore permits;
    private final LinkedBlockingDeque<Idle<T>> idle = new LinkedBlockingDeque<>();
    private final Set<T> borrowed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger waiting = new AtomicInteger();
    private volatile boolean closed;

    public ResourcePool(String name, ResourceFactory<T> factory, int maxSize, int minIdle,
                        long maxWaitMillis, long validateAfterIdleMillis) {
        if (maxSize < 1) {
            throw new IllegalArgumentException(name + " 풀 최대 크기는 1 이상이어야 합니다: " + maxSize);
        }
        this.name = name;
        this.factory = factory;
        this.maxSize = maxSize;
        this.minIdle = Math.max(0, Math.min(minIdle, maxSize));
        this.maxWaitMillis = maxWaitMillis;
        this.validateAfterIdleNanos = TimeUnit.MILLISECONDS.toNanos(validateAfterIdleMillis);
        this.permits = new Semaphore(maxSize, true);
    }

    public void warmUp() throws Exception {
        List<T> created = new ArrayList<>();
        try {
            while (created.size() < minIdle) {
                created.add(borrow());
            }
        } finally {
            created.forEach(this::release);
        }
    }

    public T borrow() throws Exception {
        if (closed) {
            throw new IllegalStateException(name + " 풀이 닫혔습니다.");
        }
        waiting.incrementAndGet();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS);
        } finally {
            waiting.decrementAndGet();
        }
        if (!acquired) {
            throw new PoolTimeoutException(name + " 풀 대기 " + maxWaitMillis + "ms 초과 (" + stats() + ")");
        }
        try {
            T resource = takeIdle();
            if (resource == null) {
                resource = factory.create();
            }
            borrowed.add(resource);
            return resource;
        } catch (Throwable e) {
            permits.release();
            throw e;
        }
    }

    public void release(T resource) {
        checkBorrowed(resource);
        idle.addFirst(new Idle<>(resource, System.nanoTime()));
        if (closed) {
            drainIdle();
        }
        permits.release();
    }

    public void invalidate(T resource) {
        checkBorrowed(resource);
        destroy(resource);
        permits.release();
    }

    public int active() {
        return borrowed.size();
    }

    public int idle() {
        return idle.size();
    }

    public int waiting() {
        return waiting.get();
    }

    public int maxSize() {
        return maxSize;
    }

    public String stats() {
        return "active=" + active() + ", idle=" + idle() + ", waiting=" + waiting() + ", max=" + maxSize;
    }

    @Override
    public void close() {
        closed = true;
        drainIdle();
    }

    private T takeIdle() {
        Idle<T> entry;
        while ((entry = idle.pollFirst()) != null) {
            if (System.nanoTime() - entry.since() < validateAfterIdleNanos || isValid(entry.resource())) {
                return entry.resource();
            }
            destroy(entry.resource());
        }
        return null;
    }

    private boolean isValid(T resource) {
        try {
            return factory.validate(resource);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void checkBorrowed(T resource) {
        if (!borrowed.remove(resource)) {
            throw new IllegalStateException(name + " 풀에서 빌린 자원이 아닙니다.");
        }
    }

    private void drainIdle() {
        Idle<T> entry;
        while ((entry = idle.pollFirst()) != null) {
            destroy(entry.resource());
        }
    }

    private void destroy(T resource) {
        try {
            factory.destroy(resource);
        } catch (Exception ignored) {
        }
    }

    private record Idle<T>(T resource, long since) {
    }
}
