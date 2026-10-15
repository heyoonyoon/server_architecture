package com.serverarchitecture;

import com.serverarchitecture.pool.PooledDataSource;
import com.serverarchitecture.redis.RedisPool;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class Server {
    private static final Logger log = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) throws Exception {
        PooledDataSource dataSource = Database.createDataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();

        RedisPool redisPool = EnrollmentRedis.createPool();
        if (!redisPool.warmUp()) {
            log.warn("Redis 연결 실패 {}:{}, 요청 시 다시 연결합니다.", EnrollmentConfig.REDIS_HOST, EnrollmentConfig.REDIS_PORT);
        }
        EnrollmentEventPublisher publisher = new EnrollmentEventPublisher(
                EnrollmentEventPublisher.createProducer(EnrollmentConfig.KAFKA_BOOTSTRAP));

        JwtProvider jwtProvider = JwtProvider.fromEnv();
        OfferingRepository offeringRepository = new OfferingRepository(dataSource);
        OfferingCatalog catalog = new OfferingCatalog(offeringRepository, EnrollmentConfig.SEMESTER);
        catalog.start(EnrollmentConfig.CATALOG_REFRESH_SECONDS);
        ServerClientServiceImpl service = new ServerClientServiceImpl(
                new StudentRepository(dataSource),
                new CourseRepository(dataSource),
                new UserRepository(dataSource),
                jwtProvider,
                catalog);
        EnrollmentServiceImpl enrollmentService = new EnrollmentServiceImpl(
                offeringRepository,
                catalog,
                new EnrollmentRedis(redisPool, EnrollmentConfig.PASS_TTL_SECONDS),
                publisher);

        io.grpc.Server server = ServerBuilder.forPort(8081)
                .addService(ServerInterceptors.intercept(service,
                        new AuthInterceptor(jwtProvider),
                        new AccessLogInterceptor(jwtProvider)))
                .addService(ServerInterceptors.intercept(enrollmentService,
                        new AuthInterceptor(jwtProvider),
                        new AccessLogInterceptor(jwtProvider)))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            try {
                server.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catalog.close();
            publisher.close();
            redisPool.close();
            dataSource.close();
        }));

        server.start();
        log.info("서버가동ON 아 아 포트번호 : {}", server.getPort());

        server.awaitTermination();
    }
}
