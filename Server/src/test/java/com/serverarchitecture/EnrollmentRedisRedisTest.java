package com.serverarchitecture;

import com.serverarchitecture.redis.RedisConnection;
import com.serverarchitecture.redis.RedisPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "REDIS_IT_PORT", matches = "\\d+")
class EnrollmentRedisRedisTest {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_IT_PORT", "0"));

    private RedisPool pool;
    private RedisConnection admin;
    private EnrollmentRedis redis;

    @BeforeEach
    void setUp() {
        pool = new RedisPool("localhost", PORT, 32, 4, 2000);
        admin = RedisConnection.connect("localhost", PORT, 2000);
        admin.call("FLUSHALL");
        admin.call("SCRIPT", "FLUSH");
        redis = new EnrollmentRedis(pool, 300);
    }

    @AfterEach
    void tearDown() {
        admin.close();
        pool.close();
    }

    @Test
    void seventeenConcurrentEnrollsFillCapacityFiveExactly() throws Exception {
        admin.call("HSET", EnrollmentConfig.SEATS_KEY, "7", "5");
        ExecutorService executor = Executors.newFixedThreadPool(17);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            String studentId = "2026" + String.format("%04d", i);
            futures.add(executor.submit(() -> {
                start.await();
                return redis.enroll(studentId, 7);
            }));
        }
        start.countDown();
        List<Long> results = new ArrayList<>();
        for (Future<Long> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        assertEquals(5, results.stream().filter(result -> result == 1).count());
        assertEquals(12, results.stream().filter(result -> result == -2).count());
        assertEquals(Map.of(7, 0), redis.remainingSeats());
        long enrolledStudents = 0;
        for (int i = 0; i < 17; i++) {
            enrolledStudents += redis.studentOfferings("2026" + String.format("%04d", i)).size();
        }
        assertEquals(5, enrolledStudents);
    }

    @Test
    void enrollAndCancelScripts() {
        admin.call("HSET", EnrollmentConfig.SEATS_KEY, "1", "2", "2", "0");

        assertEquals(1, redis.enroll("s1", 1));
        assertEquals(-1, redis.enroll("s1", 1));
        assertEquals(-2, redis.enroll("s1", 2));
        assertEquals(-3, redis.enroll("s1", 99));
        assertEquals(Set.of(1), redis.studentOfferings("s1"));
        assertEquals(Map.of(1, 1, 2, 0), redis.remainingSeats());

        assertEquals(1, redis.cancel("s1", 1));
        assertEquals(0, redis.cancel("s1", 1));
        assertEquals(Set.of(), redis.studentOfferings("s1"));
        assertEquals(Map.of(1, 2, 2, 0), redis.remainingSeats());
    }

    @Test
    void scriptsReloadAfterFlush() {
        admin.call("HSET", EnrollmentConfig.SEATS_KEY, "1", "5");
        assertEquals(1, redis.enroll("s1", 1));

        admin.call("SCRIPT", "FLUSH");

        assertEquals(1, redis.enroll("s2", 1));
        assertEquals("3", admin.call("HGET", EnrollmentConfig.SEATS_KEY, "1"));
    }

    @Test
    void queueEntryRankAndLeave() {
        redis.enterQueue("a", 100);
        redis.enterQueue("b", 200);
        redis.enterQueue("a", 300);

        assertEquals(0L, redis.queueRank("a"));
        assertEquals(1L, redis.queueRank("b"));
        assertNull(redis.queueRank("c"));
        assertEquals("300", admin.call("ZSCORE", EnrollmentConfig.SEEN_KEY, "a"));
        redis.markSeen("b", 400);
        assertEquals("400", admin.call("ZSCORE", EnrollmentConfig.SEEN_KEY, "b"));

        assertTrue(redis.leaveQueue("a"));
        assertFalse(redis.leaveQueue("a"));
        assertEquals(0L, redis.queueRank("b"));
        assertNull(admin.call("ZSCORE", EnrollmentConfig.SEEN_KEY, "a"));
    }

    @Test
    void passExpiryAndExtension() {
        long now = 1760000000000L;
        admin.call("ZADD", EnrollmentConfig.ACTIVE_KEY, String.valueOf(now + 30000), "a");
        admin.call("ZADD", EnrollmentConfig.ACTIVE_KEY, String.valueOf(now - 1), "expired");

        assertEquals(now + 30000, redis.passExpiresAt("a", now));
        assertNull(redis.passExpiresAt("expired", now));
        assertNull(redis.passExpiresAt("none", now));

        assertTrue(redis.extendPass("a", now));
        assertEquals(now + 300000, redis.passExpiresAt("a", now));
        assertFalse(redis.extendPass("expired", now));
        assertFalse(redis.extendPass("none", now));
        assertNull(admin.call("ZSCORE", EnrollmentConfig.ACTIVE_KEY, "none"));
    }

    @Test
    void openTime() {
        assertNull(redis.openAt());
        admin.call("SET", EnrollmentConfig.OPEN_KEY, " 1760000000000 ");
        assertEquals(1760000000000L, redis.openAt());
    }

    @Test
    void admitScriptReturnsCounts() throws IOException {
        String admit;
        try (InputStream input = getClass().getResourceAsStream("/redis/admit.lua")) {
            admit = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        long now = 1760000100000L;
        for (String student : List.of("a", "b", "c")) {
            admin.call("ZADD", EnrollmentConfig.QUEUE_KEY, "1", student);
            admin.call("ZADD", EnrollmentConfig.SEEN_KEY, String.valueOf(now), student);
        }
        admin.call("ZADD", EnrollmentConfig.QUEUE_KEY, "2", "gone");
        admin.call("ZADD", EnrollmentConfig.SEEN_KEY, String.valueOf(now - 60000), "gone");

        try (RedisConnection connection = pool.getResource()) {
            Object result = connection.eval(admit,
                    List.of(EnrollmentConfig.QUEUE_KEY, EnrollmentConfig.ACTIVE_KEY, EnrollmentConfig.SEEN_KEY,
                            EnrollmentConfig.MAX_KEY),
                    List.of(String.valueOf(now), "30000", "2", "30000"));

            assertEquals(List.of(2L, 1L), result);
            assertEquals(2, connection.zcard(EnrollmentConfig.ACTIVE_KEY));
            assertEquals(1, connection.zcard(EnrollmentConfig.QUEUE_KEY));
        }
    }
}
