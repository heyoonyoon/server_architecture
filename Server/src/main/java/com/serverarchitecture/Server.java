package com.serverarchitecture;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server {
    private static final Logger log = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) throws Exception {
        HikariDataSource dataSource = Database.createDataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();

        JwtProvider jwtProvider = JwtProvider.fromEnv();
        ServerClientServiceImpl service = new ServerClientServiceImpl(
                new StudentRepository(dataSource),
                new CourseRepository(dataSource),
                new UserRepository(dataSource),
                jwtProvider);

        io.grpc.Server server = ServerBuilder.forPort(8081)
                .addService(ServerInterceptors.intercept(service,
                        new AuthInterceptor(jwtProvider),
                        new AccessLogInterceptor(jwtProvider)))
                .build();

        server.start();
        log.info("서버가동ON 아 아 포트번호 : {}", server.getPort());

        server.awaitTermination();
    }
}
