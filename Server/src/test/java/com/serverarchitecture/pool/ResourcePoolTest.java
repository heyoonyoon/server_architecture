package com.serverarchitecture.pool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePoolTest {
    private static class Resource {
        volatile boolean valid = true;
        volatile boolean destroyed;
    }

    private static class Factory implements ResourceFactory<Resource> {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger validated = new AtomicInteger();
        final Set<Resource> destroyed = ConcurrentHashMap.newKeySet();
        volatile boolean failCreate;

        @Override
        public Resource create() throws Exception {
            if (failCreate) {
                throw new Exception("create failed");
            }
            created.incrementAndGet();
            return new Resource();
        }

        @Override
        public boolean validate(Resource resource) {
            validated.incrementAndGet();
            return resource.valid;
        }

        @Override
        public void destroy(Resource resource) {
            resource.destroyed = true;
            destroyed.add(resource);
        }
    }

    @Test
    void neverHandsOutMoreThanMaxSizeUnderContention() throws Exception {
        Factory factory = new Factory();
        ResourcePool<Resource> pool = new ResourcePool<>("test", factory, 5, 0, 10000, 60000);
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<Resource> seen = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                for (int j = 0; j < 40; j++) {
                    Resource resource = pool.borrow();
                    seen.add(resource);
                    peak.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                    Thread.onSpinWait();
                    inUse.decrementAndGet();
                    pool.release(resource);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertTrue(peak.get() <= 5, "peak=" + peak.get());
        assertTrue(factory.created.get() <= 5, "created=" + factory.created.get());
        assertTrue(seen.size() <= 5);
        assertEquals(0, pool.active());
        assertEquals(factory.created.get(), pool.idle());
        assertEquals(0, pool.waiting());
    }

    @Test
    void borrowTimesOutWhenExhausted() throws Exception {
        ResourcePool<Resource> pool = new ResourcePool<>("test", new Factory(), 2, 0, 100, 60000);
        pool.borrow();
        pool.borrow();

        long started = System.nanoTime();
        PoolTimeoutException e = assertThrows(PoolTimeoutException.class, pool::borrow);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis >= 90, "elapsed=" + elapsedMillis);
        assertTrue(e.getMessage().contains("active=2"), e.getMessage());
    }

    @Test
    void waiterGetsReleasedResource() throws Exception {
        ResourcePool<Resource> pool = new ResourcePool<>("test", new Factory(), 1, 0, 5000, 60000);
        Resource first = pool.borrow();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Resource> waiter = executor.submit(pool::borrow);
        while (pool.waiting() == 0) {
            Thread.onSpinWait();
        }

        pool.release(first);

        assertSame(first, waiter.get());
        executor.shutdown();
    }

    @Test
    void invalidateDestroysAndFreesCapacity() throws Exception {
        Factory factory = new Factory();
        ResourcePool<Resource> pool = new ResourcePool<>("test", factory, 1, 0, 100, 60000);
        Resource broken = pool.borrow();

        pool.invalidate(broken);
        Resource next = pool.borrow();

        assertTrue(broken.destroyed);
        assertNotSame(broken, next);
        assertEquals(0, pool.idle());
        assertEquals(1, pool.active());
    }

    @Test
    void validatesOnlyAfterIdleThreshold() throws Exception {
        Factory factory = new Factory();
        ResourcePool<Resource> pool = new ResourcePool<>("test", factory, 1, 0, 100, 60000);
        Resource resource = pool.borrow();
        pool.release(resource);

        assertSame(resource, pool.borrow());
        assertEquals(0, factory.validated.get());
    }

    @Test
    void invalidIdleResourceIsReplaced() throws Exception {
        Factory factory = new Factory();
        ResourcePool<Resource> pool = new ResourcePool<>("test", factory, 1, 0, 100, 0);
        Resource stale = pool.borrow();
        pool.release(stale);
        stale.valid = false;

        Resource fresh = pool.borrow();

        assertNotSame(stale, fresh);
        assertTrue(stale.destroyed);
        assertEquals(1, factory.validated.get());
        assertEquals(2, factory.created.get());
    }

    @Test
    void failedCreateReturnsCapacity() throws Exception {
        Factory factory = new Factory();
        ResourcePool<Resource> pool = new ResourcePool<>("test", factory, 1, 0, 100, 60000);
        factory.failCreate = true;

        assertThrows(Exception.class, pool::borrow);
        factory.failCreate = false;

        pool.borrow();
        assertEquals(1, pool.active());
    }

    @Test
    void releasingForeignOrTwiceIsRejected() throws Exception {
        ResourcePool<Resource> pool = new ResourcePool<>("test", new Factory(), 1, 0, 100, 60000);
        Resource resource = pool.borrow();
        pool.release(resource);

        assertThrows(IllegalStateException.class, () -> pool.release(resource));
        assertThrows(IllegalStateException.class, () -> pool.release(new Resource()));
        assertSame(resource, pool.borrow());
    }

    @Test
    void warmUpCreatesMinIdle() throws Exception {
        Factory factory = new Factory();
        ResourcePool<Resource> pool = new ResourcePool<>("test", factory, 5, 3, 100, 60000);

        pool.warmUp();

        assertEquals(3, factory.created.get());
        assertEquals(3, pool.idle());
        assertEquals(0, pool.active());
    }

    @Test
    void closeDestroysIdleAndLaterReleases() throws Exception {
        Factory factory = new Factory();
        ResourcePool<Resource> pool = new ResourcePool<>("test", factory, 2, 0, 100, 60000);
        Resource idleResource = pool.borrow();
        Resource activeResource = pool.borrow();
        pool.release(idleResource);

        pool.close();
        assertTrue(idleResource.destroyed);
        pool.release(activeResource);

        assertTrue(activeResource.destroyed);
        assertEquals(0, pool.idle());
        assertThrows(IllegalStateException.class, pool::borrow);
    }
}
