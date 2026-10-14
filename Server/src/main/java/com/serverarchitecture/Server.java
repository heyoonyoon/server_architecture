package com.serverarchitecture;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.TimeUnit;

public class Server {
    private static final Logger log = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) throws Exception {
        HikariDataSource dataSource = Database.createDataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();

        JedisPool jedisPool = new JedisPool(EnrollmentConfig.REDIS_HOST, EnrollmentConfig.REDIS_PORT);
        EnrollmentEventPublisher publisher = new EnrollmentEventPublisher(
                EnrollmentEventPublisher.createProducer(EnrollmentConfig.KAFKA_BOOTSTRAP));

        JwtProvider jwtProvider = JwtProvider.fromEnv();
        ServerClientServiceImpl service = new ServerClientServiceImpl(
                new StudentRepository(dataSource),
                new CourseRepository(dataSource),
                new UserRepository(dataSource),
                jwtProvider);
        EnrollmentServiceImpl enrollmentService = new EnrollmentServiceImpl(
                new OfferingRepository(dataSource),
                new EnrollmentRedis(jedisPool, EnrollmentConfig.PASS_TTL_SECONDS),
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
            publisher.close();
            jedisPool.close();
            dataSource.close();
        }));

        server.start();
        log.info("서버가동ON 아 아 포트번호 : {}", server.getPort());

        server.awaitTermination();
    }
}
