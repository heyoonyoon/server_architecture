package com.serverarchitecture.enrollment;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.redis.RedisConnection;
import com.serverarchitecture.redis.RedisPool;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnabledIfEnvironmentVariable(named = "REDIS_IT_PORT", matches = "\\d+")
class QueueWorkerRedisTest {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_IT_PORT", "0"));

    private final FakeDataService dataService = new FakeDataService();
    private Server server;
    private ManagedChannel channel;
    private RedisPool pool;
    private RedisConnection admin;
    private QueueWorker worker;
    private EnrollmentRedis redis;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(dataService).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        pool = new RedisPool("localhost", PORT, 8, 1, 2000);
        admin = RedisConnection.connect("localhost", PORT, 2000);
        admin.call("FLUSHALL");
        worker = new QueueWorker(new DataClient(channel, 3000), pool);
        redis = new EnrollmentRedis(pool, 300);
    }

    @AfterEach
    void tearDown() {
        admin.close();
        pool.close();
        channel.shutdownNow();
        server.shutdownNow();
    }

    private void offering(String id, String semester, int capacity) {
        dataService.put(DataCollections.OFFERINGS, DataClient.item(id,
                Map.of("semester", semester, "capacity", String.valueOf(capacity), "course_id", "C" + id)));
    }

    private void enrollment(String studentId, String offeringId, String status) {
        dataService.put(DataCollections.ENROLLMENTS, DataClient.item(studentId + ":" + offeringId,
                Map.of("student_id", studentId, "offering_id", offeringId, "status", status)));
    }

    @Test
    void prewarmLoadsSeatsAndStudentSetsFromData() {
        offering("8", EnrollmentConfig.SEMESTER, 3);
        offering("9", EnrollmentConfig.SEMESTER, 2);
        offering("20", "2026-1", 5);
        enrollment("s1", "8", "ENROLLED");
        enrollment("s2", "8", "ENROLLED");
        enrollment("s3", "9", "CANCELLED");
        enrollment("s4", "20", "ENROLLED");

        worker.tick();

        assertEquals(Map.of(8, 1, 9, 2), redis.remainingSeats());
        assertEquals(Set.of(8), redis.studentOfferings("s1"));
        assertEquals(Set.of(8), redis.studentOfferings("s2"));
        assertEquals(Set.of(), redis.studentOfferings("s3"));
        assertEquals(Set.of(), redis.studentOfferings("s4"));
        assertEquals("1", admin.call("GET", EnrollmentConfig.READY_KEY));

        admin.call("HSET", EnrollmentConfig.SEATS_KEY, "8", "0");
        admin.call("DEL", EnrollmentConfig.READY_KEY);
        worker.tick();
        assertEquals(Map.of(8, 0, 9, 2), redis.remainingSeats());
    }

    @Test
    void prewarmWaitsWhileSemesterHasNoOfferings() {
        offering("20", "2026-1", 5);

        worker.tick();

        assertFalse(redis.remainingSeats().containsKey(20));
        assertEquals(0L, admin.call("EXISTS", EnrollmentConfig.READY_KEY));

        offering("8", EnrollmentConfig.SEMESTER, 3);
        worker.tick();
        assertEquals(Map.of(8, 3), redis.remainingSeats());
    }

    @Test
    void tickAdmitsWaitersIntoActiveSet() {
        offering("8", EnrollmentConfig.SEMESTER, 3);
        long now = System.currentTimeMillis();
        redis.enterQueue("a", now);
        redis.enterQueue("b", now + 1);

        worker.tick();

        assertNotNull(redis.passExpiresAt("a", System.currentTimeMillis()));
        assertNotNull(redis.passExpiresAt("b", System.currentTimeMillis()));
        assertNull(redis.queueRank("a"));
    }
}
