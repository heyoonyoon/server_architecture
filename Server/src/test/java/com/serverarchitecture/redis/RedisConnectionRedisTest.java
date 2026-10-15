package com.serverarchitecture.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "REDIS_IT_PORT", matches = "\\d+")
class RedisConnectionRedisTest {
    private static final String HOST = "localhost";
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_IT_PORT", "0"));

    private RedisConnection redis;

    @BeforeEach
    void connect() {
        redis = RedisConnection.connect(HOST, PORT, 2000);
        redis.call("FLUSHALL");
    }

    @AfterEach
    void disconnect() {
        redis.close();
    }

    @Test
    void stringsAndExistence() {
        assertEquals("PONG", redis.ping());
        assertNull(redis.get("k"));
        assertFalse(redis.exists("k"));
        assertEquals("OK", redis.set("k", "학생 1"));
        assertEquals("학생 1", redis.get("k"));
        assertTrue(redis.exists("k"));
    }

    @Test
    void sortedSets() {
        assertEquals(1, redis.zadd("z", 1760000000123.0, "a"));
        assertEquals(0, redis.zadd("z", 5, "a", ZAddCondition.NX));
        assertEquals(1760000000123.0, redis.zscore("z", "a"));
        assertEquals(0, redis.zadd("z", 5, "b", ZAddCondition.XX));
        assertNull(redis.zscore("z", "b"));
        assertEquals(0, redis.zadd("z", 7, "a", ZAddCondition.XX));
        assertEquals(7.0, redis.zscore("z", "a"));
        redis.zadd("z", 3, "c");
        assertEquals(0L, redis.zrank("z", "c"));
        assertEquals(1L, redis.zrank("z", "a"));
        assertNull(redis.zrank("z", "missing"));
        assertEquals(2, redis.zcard("z"));
    }

    @Test
    void transactionRunsAtomically() {
        Transaction transaction = redis.multi();
        transaction.zadd("q", 10, "s1", ZAddCondition.NX);
        transaction.zadd("seen", 10, "s1");

        assertEquals(List.of(1L, 1L), transaction.exec());
        assertEquals(10.0, redis.zscore("q", "s1"));
        assertEquals("PONG", redis.ping());
    }

    @Test
    void pipelineAndCollections() {
        Pipeline pipeline = redis.pipelined();
        for (int i = 0; i < 5000; i++) {
            pipeline.hsetnx("seats", String.valueOf(i), "30");
            pipeline.sadd("student:" + (i % 10), String.valueOf(i));
        }
        pipeline.hsetnx("seats", "0", "99");
        List<Object> replies = pipeline.sync();

        assertEquals(10001, replies.size());
        assertEquals(0L, replies.get(replies.size() - 1));
        Map<String, String> seats = redis.hgetAll("seats");
        assertEquals(5000, seats.size());
        assertEquals("30", seats.get("0"));
        Set<String> members = redis.smembers("student:3");
        assertEquals(500, members.size());
        assertTrue(members.contains("4993"));
        assertEquals(Set.of(), redis.smembers("none"));
        assertEquals(Map.of(), redis.hgetAll("none"));
    }

    @Test
    void scriptsReturnLongsAndLists() {
        Object number = redis.eval("return tonumber(ARGV[1]) + #KEYS", List.of("a", "b"), List.of("40"));
        Object table = redis.eval("return {1, 'x', {2}, redis.call('GET', KEYS[1])}", List.of("k"), List.of());

        assertEquals(42L, number);
        assertEquals(Arrays.asList(1L, "x", List.of(2L), null), table);
    }

    @Test
    void evalshaReportsMissingScript() {
        String sha = (String) redis.call("SCRIPT", "LOAD", "return 7");
        redis.call("SCRIPT", "FLUSH");

        assertThrows(RedisNoScriptException.class, () -> redis.evalsha(sha, List.of(), List.of()));
        assertEquals(7L, redis.eval("return 7", List.of(), List.of()));
        assertEquals(7L, redis.evalsha(sha, List.of(), List.of()));
    }

    @Test
    void errorReplyKeepsConnectionUsable() {
        redis.set("str", "x");

        RedisErrorException e = assertThrows(RedisErrorException.class, () -> redis.zcard("str"));

        assertTrue(e.getMessage().startsWith("WRONGTYPE"));
        assertFalse(redis.isBroken());
        assertEquals("x", redis.get("str"));
    }

    @Test
    void poolInvalidatesKilledConnection() {
        try (RedisPool pool = new RedisPool(HOST, PORT, 2, 1, 500)) {
            assertTrue(pool.warmUp());
            RedisConnection victim = pool.getResource();
            redis.call("CLIENT", "KILL", "ID", String.valueOf(victim.call("CLIENT", "ID")));

            assertThrows(RedisConnectionException.class, victim::ping);
            assertTrue(victim.isBroken());
            victim.close();

            assertEquals("active=0, idle=0, waiting=0, max=2", pool.stats());
            try (RedisConnection fresh = pool.getResource()) {
                assertEquals("PONG", fresh.ping());
            }
            assertEquals("active=0, idle=1, waiting=0, max=2", pool.stats());
        }
    }

    @Test
    void unreadRepliesInvalidateConnection() {
        try (RedisPool pool = new RedisPool(HOST, PORT, 1, 0, 500)) {
            try (RedisConnection connection = pool.getResource()) {
                connection.pipelined().sadd("s", "1");
            }

            assertEquals("active=0, idle=0, waiting=0, max=1", pool.stats());
        }
    }

    @Test
    void exhaustedPoolThrowsDedicatedException() {
        try (RedisPool pool = new RedisPool(HOST, PORT, 1, 0, 100)) {
            RedisConnection held = pool.getResource();

            RedisPoolExhaustedException e = assertThrows(RedisPoolExhaustedException.class, pool::getResource);

            assertTrue(e.getMessage().startsWith("redis 풀 대기 100ms 초과"), e.getMessage());
            held.close();
            pool.getResource().close();
        }
    }

    @Test
    void unreachableServerIsConnectionFailure() {
        try (RedisPool pool = new RedisPool(HOST, 1, 1, 1, 100)) {
            assertFalse(pool.warmUp());
            RedisException e = assertThrows(RedisException.class, pool::getResource);

            assertInstanceOf(RedisConnectionException.class, e);
        }
    }

    @Test
    void poolSharesConnectionsAcrossThreads() throws Exception {
        try (RedisPool pool = new RedisPool(HOST, PORT, 8, 2, 2000)) {
            ExecutorService executor = Executors.newFixedThreadPool(64);
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                futures.add(executor.submit(() -> {
                    try (RedisConnection connection = pool.getResource()) {
                        return (Long) connection.call("INCR", "counter");
                    }
                }));
            }
            for (Future<Long> future : futures) {
                future.get();
            }
            executor.shutdown();

            assertEquals("2000", redis.get("counter"));
            assertTrue(pool.stats().startsWith("active=0, idle="));
            List<?> clients = Arrays.asList(((String) redis.call("CLIENT", "LIST")).split("\n"));
            assertTrue(clients.size() <= 9, "clients=" + clients.size());
        }
    }
}
