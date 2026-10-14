package com.serverarchitecture;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisNoScriptException;
import redis.clients.jedis.params.ZAddParams;

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
    private final JedisPool pool;
    private final long passTtlMillis;
    private final Script enrollScript = Script.load("/redis/enroll.lua");
    private final Script cancelScript = Script.load("/redis/cancel.lua");

    public EnrollmentRedis(JedisPool pool, int passTtlSeconds) {
        this.pool = pool;
        this.passTtlMillis = passTtlSeconds * 1000L;
    }

    public Long passExpiresAt(String studentId, long now) {
        try (Jedis jedis = pool.getResource()) {
            Double score = jedis.zscore(EnrollmentConfig.ACTIVE_KEY, studentId);
            return score != null && score > now ? score.longValue() : null;
        }
    }

    public boolean extendPass(String studentId, long now) {
        try (Jedis jedis = pool.getResource()) {
            Double score = jedis.zscore(EnrollmentConfig.ACTIVE_KEY, studentId);
            if (score == null || score <= now) {
                return false;
            }
            jedis.zadd(EnrollmentConfig.ACTIVE_KEY, now + passTtlMillis, studentId, ZAddParams.zAddParams().xx());
            return true;
        }
    }

    public Long queueRank(String studentId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.zrank(EnrollmentConfig.QUEUE_KEY, studentId);
        }
    }

    public void enterQueue(String studentId, long now) {
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd(EnrollmentConfig.QUEUE_KEY, now, studentId, ZAddParams.zAddParams().nx());
        }
    }

    public Set<Integer> studentOfferings(String studentId) {
        try (Jedis jedis = pool.getResource()) {
            Set<Integer> offeringIds = new LinkedHashSet<Integer>();
            for (String member : jedis.smembers(EnrollmentConfig.studentOfferingsKey(studentId))) {
                offeringIds.add(Integer.parseInt(member));
            }
            return offeringIds;
        }
    }

    public Map<Integer, Integer> remainingSeats() {
        try (Jedis jedis = pool.getResource()) {
            Map<Integer, Integer> seats = new HashMap<Integer, Integer>();
            for (Map.Entry<String, String> entry : jedis.hgetAll(EnrollmentConfig.SEATS_KEY).entrySet()) {
                seats.put(Integer.parseInt(entry.getKey()), Integer.parseInt(entry.getValue()));
            }
            return seats;
        }
    }

    public long enroll(String studentId, int offeringId) {
        return run(enrollScript, studentId, offeringId);
    }

    public long cancel(String studentId, int offeringId) {
        return run(cancelScript, studentId, offeringId);
    }

    private long run(Script script, String studentId, int offeringId) {
        List<String> keys = List.of(EnrollmentConfig.SEATS_KEY, EnrollmentConfig.studentOfferingsKey(studentId));
        List<String> args = List.of(String.valueOf(offeringId));

        try (Jedis jedis = pool.getResource()) {
            try {
                return (Long) jedis.evalsha(script.sha(), keys, args);
            } catch (JedisNoScriptException e) {
                return (Long) jedis.eval(script.source(), keys, args);
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
