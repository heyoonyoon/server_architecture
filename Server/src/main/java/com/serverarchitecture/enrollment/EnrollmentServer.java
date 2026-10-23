package com.serverarchitecture.enrollment;

import com.serverarchitecture.AccessLogInterceptor;
import com.serverarchitecture.AuthInterceptor;
import com.serverarchitecture.EnrollmentServiceGrpc;
import com.serverarchitecture.GrpcServerRunner;
import com.serverarchitecture.JwtProvider;
import com.serverarchitecture.Role;
import com.serverarchitecture.ServiceConfig;
import com.serverarchitecture.redis.RedisPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class EnrollmentServer {
    private static final Logger log = LoggerFactory.getLogger(EnrollmentServer.class);

    static final Map<String, Set<Role>> PERMISSIONS = Map.ofEntries(
            Map.entry(EnrollmentServiceGrpc.getEnterQueueMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getGetQueueStatusMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getWatchQueueMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getLeaveQueueMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getGetScheduleMethod().getFullMethodName(), Set.of(Role.STUDENT, Role.PROFESSOR)),
            Map.entry(EnrollmentServiceGrpc.getListOfferingsMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getEnrollMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getCancelEnrollmentMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getListMyEnrollmentsMethod().getFullMethodName(), Set.of(Role.STUDENT)));

    public static void main(String[] args) throws Exception {
        RedisPool redisPool = EnrollmentRedis.createPool();
        if (!redisPool.warmUp()) {
            log.warn("Redis 연결 실패 {}:{}, 요청 시 다시 연결합니다.", EnrollmentConfig.REDIS_HOST, EnrollmentConfig.REDIS_PORT);
        }
        EnrollmentEventPublisher publisher = new EnrollmentEventPublisher(
                EnrollmentEventPublisher.createProducer(EnrollmentConfig.KAFKA_BOOTSTRAP));

        JwtProvider jwtProvider = JwtProvider.fromEnv();
        AcademicClient academic = AcademicClient.fromEnv(jwtProvider);
        OfferingCatalog catalog = new OfferingCatalog(academic, EnrollmentConfig.SEMESTER);
        catalog.start(EnrollmentConfig.CATALOG_REFRESH_SECONDS);
        EnrollmentServiceImpl service = new EnrollmentServiceImpl(
                academic,
                catalog,
                new EnrollmentRedis(redisPool, EnrollmentConfig.PASS_TTL_SECONDS),
                publisher);

        GrpcServerRunner.run("enrollment", ServiceConfig.ENROLLMENT_PORT, service,
                List.of(new AuthInterceptor(jwtProvider, PERMISSIONS), new AccessLogInterceptor(jwtProvider)),
                catalog, publisher, redisPool, academic);
    }
}
