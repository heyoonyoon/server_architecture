package com.serverarchitecture.enrollment;

import com.serverarchitecture.QueueState;
import com.serverarchitecture.QueueStatusResponse;
import com.serverarchitecture.WatchQueueRequest;
import com.serverarchitecture.redis.RedisConnectionException;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchQueueTest {
    private static final long INTERVAL_MS = 20;

    private FakeEnrollmentRedis redis;
    private EnrollmentHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        redis = new FakeEnrollmentRedis();
        harness = new EnrollmentHarness(redis, INTERVAL_MS, 2);
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    private Collector watch(String studentId) {
        Collector collector = new Collector();
        harness.async(studentId).watchQueue(WatchQueueRequest.getDefaultInstance(), collector);
        return collector;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "조건을 기다리다 시간 초과");
            Thread.sleep(5);
        }
    }

    @Test
    void notInQueueSendsOneStatusAndCompletes() throws Exception {
        Collector collector = watch("s1");

        assertTrue(collector.done.await(2, TimeUnit.SECONDS));
        assertNull(collector.error);
        assertEquals(List.of(QueueState.NOT_IN_QUEUE), collector.states());
        await(() -> harness.watcher.activeWatchers() == 0);
    }

    @Test
    void sendsFirstStatusThenOnlyChangesAndCompletesOnAdmitted() throws Exception {
        redis.queue("a", "b", "c", "s1");
        Collector collector = watch("s1");
        await(() -> collector.messages.size() == 1);
        assertEquals(4, collector.messages.get(0).getPosition());

        int readsBefore = redis.statusReads.get();
        await(() -> redis.statusReads.get() >= readsBefore + 5);
        assertEquals(1, collector.messages.size());

        redis.dequeue("a");
        await(() -> collector.messages.size() == 2);
        assertEquals(3, collector.messages.get(1).getPosition());

        redis.dequeue("b");
        redis.dequeue("c");
        await(() -> collector.last().getPosition() == 1);

        redis.admit("s1", System.currentTimeMillis() + 30_000);
        assertTrue(collector.done.await(2, TimeUnit.SECONDS));
        assertNull(collector.error);
        assertEquals(QueueState.ADMITTED, collector.last().getState());
        assertTrue(collector.last().getPassRemainingSeconds() > 0);
        for (int i = 1; i < collector.messages.size(); i++) {
            assertNotEquals(collector.messages.get(i - 1), collector.messages.get(i));
        }
        assertTrue(collector.messages.size() <= 5, collector.messages.toString());
        await(() -> harness.watcher.activeWatchers() == 0);
    }

    @Test
    void openStreamKeepsWaiterSeen() throws Exception {
        redis.queue("s1");
        Collector collector = watch("s1");
        await(() -> collector.messages.size() == 1);
        long firstSeen = redis.seenAt("s1");

        await(() -> redis.seenMarks.get() >= 5 && redis.seenAt("s1") > firstSeen);

        redis.dequeue("s1");
        assertTrue(collector.done.await(2, TimeUnit.SECONDS));
        assertEquals(QueueState.NOT_IN_QUEUE, collector.last().getState());
    }

    @Test
    void clientCancelRemovesWatcher() throws Exception {
        redis.queue("s1");
        Context.CancellableContext context = Context.current().withCancellation();
        Collector collector = new Collector();
        context.run(() -> harness.async("s1").watchQueue(WatchQueueRequest.getDefaultInstance(), collector));
        await(() -> collector.messages.size() == 1);
        assertEquals(1, harness.watcher.activeWatchers());

        context.cancel(null);

        await(() -> harness.watcher.activeWatchers() == 0);
        int marks = redis.seenMarks.get();
        Thread.sleep(INTERVAL_MS * 5);
        assertEquals(marks, redis.seenMarks.get());
    }

    @Test
    void deadlineRemovesWatcher() throws Exception {
        redis.queue("s1");
        Collector collector = new Collector();
        harness.async("s1").withDeadlineAfter(150, TimeUnit.MILLISECONDS)
                .watchQueue(WatchQueueRequest.getDefaultInstance(), collector);

        assertTrue(collector.done.await(2, TimeUnit.SECONDS));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, Status.fromThrowable(collector.error).getCode());
        await(() -> harness.watcher.activeWatchers() == 0);
    }

    @Test
    void watcherLimitRejectsExtraStreams() throws Exception {
        redis.queue("s1", "s2", "s3");
        Collector first = watch("s1");
        Collector second = watch("s2");
        await(() -> first.messages.size() == 1 && second.messages.size() == 1);

        Collector third = watch("s3");

        assertTrue(third.done.await(2, TimeUnit.SECONDS));
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, Status.fromThrowable(third.error).getCode());
        assertEquals(2, harness.watcher.activeWatchers());

        redis.dequeue("s1");
        assertTrue(first.done.await(2, TimeUnit.SECONDS));
        Collector fourth = watch("s3");
        await(() -> fourth.messages.size() == 1);
    }

    @Test
    void redisFailureOnFirstCheckIsUnavailable() throws Exception {
        redis.failure = new RedisConnectionException("down", null);

        Collector collector = watch("s1");

        assertTrue(collector.done.await(2, TimeUnit.SECONDS));
        assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(collector.error).getCode());
        await(() -> harness.watcher.activeWatchers() == 0);
    }

    @Test
    void persistentRedisFailureEndsStream() throws Exception {
        redis.queue("s1");
        Collector collector = watch("s1");
        await(() -> collector.messages.size() == 1);

        redis.failure = new RedisConnectionException("down", null);

        assertTrue(collector.done.await(2, TimeUnit.SECONDS));
        assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(collector.error).getCode());
        await(() -> harness.watcher.activeWatchers() == 0);
    }

    private static final class Collector implements StreamObserver<QueueStatusResponse> {
        final List<QueueStatusResponse> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        public void onNext(QueueStatusResponse value) {
            messages.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            done.countDown();
        }

        @Override
        public void onCompleted() {
            done.countDown();
        }

        QueueStatusResponse last() {
            return messages.get(messages.size() - 1);
        }

        List<QueueState> states() {
            return messages.stream().map(QueueStatusResponse::getState).toList();
        }
    }
}
