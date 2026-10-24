package com.serverarchitecture.enrollment;

import com.serverarchitecture.CustomException;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class StudentLock {
    private static final Logger log = LoggerFactory.getLogger(StudentLock.class);
    private static final long MIN_RETRY_MS = 10;
    private static final long MAX_RETRY_MS = 30;

    private final EnrollmentRedis redis;
    private final long ttlMillis;
    private final long waitMillis;

    public StudentLock(EnrollmentRedis redis, long ttlMillis, long waitMillis) {
        this.redis = redis;
        this.ttlMillis = ttlMillis;
        this.waitMillis = waitMillis;
    }

    public <T> T call(String studentId, Supplier<T> action) {
        String token = acquire(studentId);
        try {
            return action.get();
        } finally {
            release(studentId, token);
        }
    }

    private String acquire(String studentId) {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (!redis.tryLockStudent(studentId, token, ttlMillis)) {
            if (System.nanoTime() >= deadline) {
                throw busy();
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_RETRY_MS, MAX_RETRY_MS + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw busy();
            }
        }
        return token;
    }

    private void release(String studentId, String token) {
        try {
            if (!redis.unlockStudent(studentId, token)) {
                log.warn("학생 잠금이 이미 만료되었습니다 student={}", studentId);
            }
        } catch (RuntimeException e) {
            log.warn("학생 잠금 해제 실패 student={}, {}ms 후 만료됩니다: {}", studentId, ttlMillis, e.getMessage());
        }
    }

    private CustomException busy() {
        return new CustomException(Status.Code.ABORTED, "이전 신청 요청을 처리하고 있습니다. 잠시 후 다시 시도하세요.");
    }
}
