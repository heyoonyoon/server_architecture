package com.serverarchitecture.enrollment;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.data.api.Item;
import com.serverarchitecture.redis.Pipeline;
import com.serverarchitecture.redis.RedisConnection;
import com.serverarchitecture.redis.RedisPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class QueueWorker {
    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);
    private static final long RETRY_DELAY_MS = 3000;
    private static final long STATUS_LOG_INTERVAL_MS = 10000;

    private final DataClient data;
    private final RedisPool redisPool;
    private final String admitScript;
    private long admittedSinceLastLog;
    private long droppedSinceLastLog;
    private long lastStatusLogAt;

    public QueueWorker(DataClient data, RedisPool redisPool) throws IOException {
        this.data = data;
        this.redisPool = redisPool;
        this.admitScript = loadScript("/redis/admit.lua");
    }

    public static void main(String[] args) throws Exception {
        log.info("대기열 워커 시작 semester={} maxActive={} claimSeconds={} waiterTimeoutSeconds={} tickMs={}",
                EnrollmentConfig.SEMESTER, EnrollmentConfig.QUEUE_MAX_ACTIVE, EnrollmentConfig.PASS_CLAIM_SECONDS,
                EnrollmentConfig.WAITER_TIMEOUT_SECONDS, EnrollmentConfig.WORKER_TICK_MS);

        DataClient data = DataClient.fromEnv();
        RedisPool redisPool = EnrollmentRedis.createPool();
        QueueWorker worker = new QueueWorker(data, redisPool);

        worker.waitForRedis();
        worker.waitForData();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "queue-worker");
            thread.setDaemon(false);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(worker::tick, 0, EnrollmentConfig.WORKER_TICK_MS, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("대기열 워커 종료 중");
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            redisPool.close();
            try {
                data.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("대기열 워커 종료");
        }, "queue-worker-shutdown"));
    }

    private static String loadScript(String path) throws IOException {
        try (InputStream inputStream = QueueWorker.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IOException("스크립트를 찾을 수 없습니다: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void waitForRedis() throws InterruptedException {
        while (true) {
            try (RedisConnection redis = redisPool.getResource()) {
                redis.ping();
                log.info("Redis 연결 {}:{}", EnrollmentConfig.REDIS_HOST, EnrollmentConfig.REDIS_PORT);
                return;
            } catch (RuntimeException e) {
                log.warn("Redis 연결 실패, {}ms 후 재시도: {}", RETRY_DELAY_MS, e.getMessage());
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }

    private void waitForData() throws InterruptedException {
        while (true) {
            try {
                if (data.count(DataCollections.OFFERINGS) > 0) {
                    return;
                }
                log.info("개설 강의 데이터가 아직 없습니다. 학사 서비스의 시드 적재를 기다립니다.");
            } catch (RuntimeException e) {
                log.warn("Data 서버 확인 실패: {}", e.getMessage());
            }
            Thread.sleep(RETRY_DELAY_MS);
        }
    }

    private void prewarm(RedisConnection redis) {
        Map<String, Integer> remaining = new HashMap<String, Integer>();
        for (Item offering : data.readWhere(DataCollections.OFFERINGS, "semester", EnrollmentConfig.SEMESTER)) {
            remaining.put(offering.getId(), Integer.parseInt(offering.getFieldsOrDefault("capacity", "0")));
        }

        if (remaining.isEmpty()) {
            log.warn("학기 {}의 개설 강의가 없어 프리웜을 미룹니다.", EnrollmentConfig.SEMESTER);
            return;
        }

        Pipeline pipeline = redis.pipelined();
        int enrollments = 0;
        for (Item enrollment : data.readWhere(DataCollections.ENROLLMENTS, "status", "ENROLLED")) {
            String offeringId = enrollment.getFieldsOrDefault("offering_id", "");
            if (!remaining.containsKey(offeringId)) {
                continue;
            }
            remaining.merge(offeringId, -1, Integer::sum);
            pipeline.sadd(EnrollmentConfig.studentOfferingsKey(enrollment.getFieldsOrDefault("student_id", "")),
                    offeringId);
            enrollments++;
        }
        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            pipeline.hsetnx(EnrollmentConfig.SEATS_KEY, entry.getKey(), String.valueOf(entry.getValue()));
        }
        pipeline.sync();

        redis.set(EnrollmentConfig.READY_KEY, "1");
        log.info("프리웜 완료 semester={} offerings={} enrollments={}", EnrollmentConfig.SEMESTER, remaining.size(), enrollments);
    }

    void tick() {
        try (RedisConnection redis = redisPool.getResource()) {
            if (!redis.exists(EnrollmentConfig.READY_KEY)) {
                prewarm(redis);
            }
            long now = System.currentTimeMillis();
            List<?> result = (List<?>) redis.eval(admitScript,
                    List.of(EnrollmentConfig.QUEUE_KEY, EnrollmentConfig.ACTIVE_KEY, EnrollmentConfig.SEEN_KEY,
                            EnrollmentConfig.MAX_KEY),
                    List.of(String.valueOf(now),
                            String.valueOf(EnrollmentConfig.PASS_CLAIM_SECONDS * 1000L),
                            String.valueOf(EnrollmentConfig.QUEUE_MAX_ACTIVE),
                            String.valueOf(EnrollmentConfig.WAITER_TIMEOUT_SECONDS * 1000L)));
            long admitted = ((Number) result.get(0)).longValue();
            long dropped = ((Number) result.get(1)).longValue();
            admittedSinceLastLog += admitted;
            droppedSinceLastLog += dropped;

            if (admitted > 0 || dropped > 0) {
                log.info("입장 {}명 이탈 {}명 queue={} active={}", admitted, dropped,
                        redis.zcard(EnrollmentConfig.QUEUE_KEY), redis.zcard(EnrollmentConfig.ACTIVE_KEY));
            }
            if (now - lastStatusLogAt >= STATUS_LOG_INTERVAL_MS) {
                log.info("대기열 상태 queue={} active={} admittedLast{}s={} droppedLast{}s={}",
                        redis.zcard(EnrollmentConfig.QUEUE_KEY), redis.zcard(EnrollmentConfig.ACTIVE_KEY),
                        STATUS_LOG_INTERVAL_MS / 1000, admittedSinceLastLog,
                        STATUS_LOG_INTERVAL_MS / 1000, droppedSinceLastLog);
                admittedSinceLastLog = 0;
                droppedSinceLastLog = 0;
                lastStatusLogAt = now;
            }
        } catch (RuntimeException e) {
            log.error("입장 처리 실패: {}", e.getMessage());
        }
    }
}
