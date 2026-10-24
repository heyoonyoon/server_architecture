package com.serverarchitecture.enrollment;

import com.serverarchitecture.EnrollRequest;
import com.serverarchitecture.redis.RedisConnection;
import com.serverarchitecture.redis.RedisPool;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "REDIS_IT_PORT", matches = "\\d+")
class EnrollmentRaceRedisTest {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_IT_PORT", "0"));
    private static final int ROUNDS = 10;

    private RedisConnection admin;
    private final List<RedisPool> pools = new ArrayList<>();
    private final List<EnrollmentHarness> servers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        admin = RedisConnection.connect("localhost", PORT, 2000);
        admin.call("FLUSHALL");
        for (int i = 0; i < 2; i++) {
            RedisPool pool = new RedisPool("localhost", PORT, 16, 2, 2000);
            pools.add(pool);
            EnrollmentHarness server = new EnrollmentHarness(new SlowReadRedis(pool), 1000, 10);
            server.academicService.offering(8, "C1", "자바", 100, List.of(), "MON", "09:00", "10:30");
            server.academicService.offering(9, "C2", "C++", 100, List.of(), "MON", "10:00", "11:30");
            servers.add(server);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (EnrollmentHarness server : servers) {
            server.close();
        }
        pools.forEach(RedisPool::close);
        admin.close();
    }

    @Test
    void twoServerCopiesAdmitOnlyOneOfTwoConflictingEnrollsPerStudent() throws Exception {
        admin.call("HSET", EnrollmentConfig.SEATS_KEY, "8", "100", "9", "100");
        long expiresAt = System.currentTimeMillis() + 60_000;
        ExecutorService executor = Executors.newFixedThreadPool(2 * ROUNDS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Status.Code>> futures = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            String studentId = "6022" + String.format("%04d", round);
            admin.call("ZADD", EnrollmentConfig.ACTIVE_KEY, String.valueOf(expiresAt), studentId);
            for (int copy = 0; copy < 2; copy++) {
                EnrollmentHarness server = servers.get(copy);
                server.academicService.student(studentId, 18);
                int offeringId = copy == 0 ? 8 : 9;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        server.blocking(studentId).enroll(EnrollRequest.newBuilder().setOfferingId(offeringId).build());
                        return Status.Code.OK;
                    } catch (StatusRuntimeException e) {
                        return e.getStatus().getCode();
                    }
                }));
            }
        }
        start.countDown();
        List<Status.Code> results = new ArrayList<>();
        for (Future<Status.Code> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        assertEquals(ROUNDS, results.stream().filter(code -> code == Status.Code.OK).count(), results.toString());
        assertEquals(ROUNDS, results.stream().filter(code -> code == Status.Code.ABORTED).count(), results.toString());
        EnrollmentRedis reader = new EnrollmentRedis(pools.get(0), 300);
        int seatsTaken = 0;
        for (int round = 0; round < ROUNDS; round++) {
            String studentId = "6022" + String.format("%04d", round);
            Set<Integer> offerings = reader.studentOfferings(studentId);
            assertEquals(1, offerings.size(), studentId);
            assertEquals(0L, admin.call("EXISTS", EnrollmentConfig.studentLockKey(studentId)));
            seatsTaken += offerings.size();
        }
        int remaining = reader.remainingSeats().get(8) + reader.remainingSeats().get(9);
        assertEquals(200 - seatsTaken, remaining);
    }

    private static final class SlowReadRedis extends EnrollmentRedis {
        SlowReadRedis(RedisPool pool) {
            super(pool, 300);
        }

        @Override
        public Set<Integer> studentOfferings(String studentId) {
            Set<Integer> offerings = super.studentOfferings(studentId);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return offerings;
        }
    }
}
