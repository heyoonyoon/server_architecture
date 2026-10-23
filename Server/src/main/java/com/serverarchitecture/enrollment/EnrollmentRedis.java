package com.serverarchitecture.enrollment;

import com.serverarchitecture.redis.RedisConnection;
import com.serverarchitecture.redis.RedisNoScriptException;
import com.serverarchitecture.redis.RedisPool;
import com.serverarchitecture.redis.Transaction;
import com.serverarchitecture.redis.ZAddCondition;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EnrollmentRedis {
    private final RedisPool pool;
    private final long passTtlMillis;
    private final Script enrollScript = Script.load("/redis/enroll.lua");
    private final Script cancelScript = Script.load("/redis/cancel.lua");
    private final Script leaveScript = Script.load("/redis/leave.lua");
    private final Script unlockScript = Script.load("/redis/unlock.lua");

    public EnrollmentRedis(RedisPool pool, int passTtlSeconds) {
        this.pool = pool;
        this.passTtlMillis = passTtlSeconds * 1000L;
    }

    public static RedisPool createPool() {
        return new RedisPool(EnrollmentConfig.REDIS_HOST, EnrollmentConfig.REDIS_PORT, EnrollmentConfig.REDIS_POOL_MAX,
                EnrollmentConfig.REDIS_POOL_MIN_IDLE, EnrollmentConfig.REDIS_POOL_MAX_WAIT_MS);
    }

    public Long passExpiresAt(String studentId, long now) {
        try (RedisConnection redis = pool.getResource()) {
            Double score = redis.zscore(EnrollmentConfig.ACTIVE_KEY, studentId);
            return score != null && score > now ? score.longValue() : null;
        }
    }

    public boolean extendPass(String studentId, long now) {
        try (RedisConnection redis = pool.getResource()) {
            Double score = redis.zscore(EnrollmentConfig.ACTIVE_KEY, studentId);
            if (score == null || score <= now) {
                return false;
            }
            redis.zadd(EnrollmentConfig.ACTIVE_KEY, now + passTtlMillis, studentId, ZAddCondition.XX);
            return true;
        }
    }

    public Long queueRank(String studentId) {
        try (RedisConnection redis = pool.getResource()) {
            return redis.zrank(EnrollmentConfig.QUEUE_KEY, studentId);
        }
    }

    public void enterQueue(String studentId, long now) {
        try (RedisConnection redis = pool.getResource()) {
            Transaction transaction = redis.multi();
            transaction.zadd(EnrollmentConfig.QUEUE_KEY, now, studentId, ZAddCondition.NX);
            transaction.zadd(EnrollmentConfig.SEEN_KEY, now, studentId);
            transaction.exec();
        }
    }

    public void markSeen(String studentId, long now) {
        try (RedisConnection redis = pool.getResource()) {
            redis.zadd(EnrollmentConfig.SEEN_KEY, now, studentId);
        }
    }

    public boolean leaveQueue(String studentId) {
        List<String> keys = List.of(EnrollmentConfig.QUEUE_KEY, EnrollmentConfig.ACTIVE_KEY, EnrollmentConfig.SEEN_KEY);
        return run(leaveScript, keys, List.of(studentId)) > 0;
    }

    public Long openAt() {
        try (RedisConnection redis = pool.getResource()) {
            String value = redis.get(EnrollmentConfig.OPEN_KEY);
            return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
        }
    }

    public Set<Integer> studentOfferings(String studentId) {
        try (RedisConnection redis = pool.getResource()) {
            Set<Integer> offeringIds = new LinkedHashSet<Integer>();
            for (String member : redis.smembers(EnrollmentConfig.studentOfferingsKey(studentId))) {
                offeringIds.add(Integer.parseInt(member));
            }
            return offeringIds;
        }
    }

    public Map<Integer, Integer> remainingSeats() {
        try (RedisConnection redis = pool.getResource()) {
            Map<Integer, Integer> seats = new HashMap<Integer, Integer>();
            for (Map.Entry<String, String> entry : redis.hgetAll(EnrollmentConfig.SEATS_KEY).entrySet()) {
                seats.put(Integer.parseInt(entry.getKey()), Integer.parseInt(entry.getValue()));
            }
            return seats;
        }
    }

    public long enroll(String studentId, int offeringId) {
        return run(enrollScript, seatKeys(studentId), List.of(String.valueOf(offeringId)));
    }

    public long cancel(String studentId, int offeringId) {
        return run(cancelScript, seatKeys(studentId), List.of(String.valueOf(offeringId)));
    }

    public boolean tryLockStudent(String studentId, String token, long ttlMillis) {
        try (RedisConnection redis = pool.getResource()) {
            return "OK".equals(redis.call("SET", EnrollmentConfig.studentLockKey(studentId), token,
                    "NX", "PX", String.valueOf(ttlMillis)));
        }
    }

    public boolean unlockStudent(String studentId, String token) {
        return run(unlockScript, List.of(EnrollmentConfig.studentLockKey(studentId)), List.of(token)) > 0;
    }

    private List<String> seatKeys(String studentId) {
        return List.of(EnrollmentConfig.SEATS_KEY, EnrollmentConfig.studentOfferingsKey(studentId));
    }

    private long run(Script script, List<String> keys, List<String> args) {
        try (RedisConnection redis = pool.getResource()) {
            try {
                return (Long) redis.evalsha(script.sha(), keys, args);
            } catch (RedisNoScriptException e) {
                return (Long) redis.eval(script.source(), keys, args);
            }
        }
    }

    private record Script(String source, String sha) {
        static Script load(String resource) {
            try (InputStream input = EnrollmentRedis.class.getResourceAsStream(resource)) {
                String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                byte[] digest = MessageDigest.getInstance("SHA-1").digest(source.getBytes(StandardCharsets.UTF_8));
                return new Script(source, HexFormat.of().formatHex(digest));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
