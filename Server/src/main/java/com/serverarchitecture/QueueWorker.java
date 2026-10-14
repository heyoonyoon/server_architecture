package com.serverarchitecture;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class QueueWorker {
    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);
    private static final long RETRY_DELAY_MS = 3000;
    private static final long STATUS_LOG_INTERVAL_MS = 10000;

    private final HikariDataSource dataSource;
    private final JedisPool jedisPool;
    private final String admitScript;
    private long admittedSinceLastLog;
    private long droppedSinceLastLog;
    private long lastStatusLogAt;

    public QueueWorker(HikariDataSource dataSource, JedisPool jedisPool) throws IOException {
        this.dataSource = dataSource;
        this.jedisPool = jedisPool;
        this.admitScript = loadScript("/redis/admit.lua");
    }

    public static void main(String[] args) throws Exception {
        log.info("대기열 워커 시작 semester={} maxActive={} claimSeconds={} waiterTimeoutSeconds={} tickMs={}",
                EnrollmentConfig.SEMESTER, EnrollmentConfig.QUEUE_MAX_ACTIVE, EnrollmentConfig.PASS_CLAIM_SECONDS,
                EnrollmentConfig.WAITER_TIMEOUT_SECONDS, EnrollmentConfig.WORKER_TICK_MS);

        HikariDataSource dataSource = connectDatabase();
        JedisPool jedisPool = EnrollmentRedis.createPool();
        QueueWorker worker = new QueueWorker(dataSource, jedisPool);

        worker.waitForRedis();
        worker.waitForTables();

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
            jedisPool.close();
            dataSource.close();
            log.info("대기열 워커 종료");
        }, "queue-worker-shutdown"));
    }

    private static HikariDataSource connectDatabase() throws InterruptedException {
        while (true) {
            try {
                return Database.createDataSource();
            } catch (RuntimeException e) {
                log.warn("DB 연결 실패, {}ms 후 재시도: {}", RETRY_DELAY_MS, e.getMessage());
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
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
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                log.info("Redis 연결 {}:{}", EnrollmentConfig.REDIS_HOST, EnrollmentConfig.REDIS_PORT);
                return;
            } catch (RuntimeException e) {
                log.warn("Redis 연결 실패, {}ms 후 재시도: {}", RETRY_DELAY_MS, e.getMessage());
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }

    private void waitForTables() throws InterruptedException {
        String sql = "SELECT to_regclass('public.course_offering') IS NOT NULL AND to_regclass('public.enrollment') IS NOT NULL";
        while (true) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next() && resultSet.getBoolean(1)) {
                    return;
                }
                log.info("수강신청 테이블이 아직 없습니다. 앱 서버의 마이그레이션을 기다립니다.");
            } catch (SQLException e) {
                log.warn("DB 확인 실패: {}", e.getMessage());
            }
            Thread.sleep(RETRY_DELAY_MS);
        }
    }

    private void prewarm(Jedis jedis) throws SQLException {
        String seatsSql = """
                SELECT o.offering_id, o.capacity - COUNT(e.student_id) AS remaining
                FROM course_offering o
                LEFT JOIN enrollment e ON e.offering_id = o.offering_id AND e.status = 'ENROLLED'
                WHERE o.semester = ?
                GROUP BY o.offering_id, o.capacity
                """;
        String enrollmentSql = """
                SELECT e.student_id, e.offering_id
                FROM enrollment e
                JOIN course_offering o ON o.offering_id = e.offering_id
                WHERE o.semester = ? AND e.status = 'ENROLLED'
                """;

        int offerings = 0;
        int enrollments = 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement seatsStatement = connection.prepareStatement(seatsSql);
             PreparedStatement enrollmentStatement = connection.prepareStatement(enrollmentSql)) {
            Pipeline pipeline = jedis.pipelined();

            seatsStatement.setString(1, EnrollmentConfig.SEMESTER);
            try (ResultSet resultSet = seatsStatement.executeQuery()) {
                while (resultSet.next()) {
                    pipeline.hsetnx(EnrollmentConfig.SEATS_KEY,
                            String.valueOf(resultSet.getInt("offering_id")),
                            String.valueOf(resultSet.getInt("remaining")));
                    offerings++;
                }
            }

            enrollmentStatement.setString(1, EnrollmentConfig.SEMESTER);
            try (ResultSet resultSet = enrollmentStatement.executeQuery()) {
                while (resultSet.next()) {
                    pipeline.sadd(EnrollmentConfig.studentOfferingsKey(resultSet.getString("student_id")),
                            String.valueOf(resultSet.getInt("offering_id")));
                    enrollments++;
                }
            }

            pipeline.sync();
        }

        jedis.set(EnrollmentConfig.READY_KEY, "1");
        log.info("프리웜 완료 semester={} offerings={} enrollments={}", EnrollmentConfig.SEMESTER, offerings, enrollments);
    }

    private void tick() {
        try (Jedis jedis = jedisPool.getResource()) {
            if (!jedis.exists(EnrollmentConfig.READY_KEY)) {
                prewarm(jedis);
            }
            long now = System.currentTimeMillis();
            List<?> result = (List<?>) jedis.eval(admitScript,
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
                        jedis.zcard(EnrollmentConfig.QUEUE_KEY), jedis.zcard(EnrollmentConfig.ACTIVE_KEY));
            }
            if (now - lastStatusLogAt >= STATUS_LOG_INTERVAL_MS) {
                log.info("대기열 상태 queue={} active={} admittedLast{}s={} droppedLast{}s={}",
                        jedis.zcard(EnrollmentConfig.QUEUE_KEY), jedis.zcard(EnrollmentConfig.ACTIVE_KEY),
                        STATUS_LOG_INTERVAL_MS / 1000, admittedSinceLastLog,
                        STATUS_LOG_INTERVAL_MS / 1000, droppedSinceLastLog);
                admittedSinceLastLog = 0;
                droppedSinceLastLog = 0;
                lastStatusLogAt = now;
            }
        } catch (SQLException | RuntimeException e) {
            log.error("입장 처리 실패: {}", e.getMessage());
        }
    }
}
