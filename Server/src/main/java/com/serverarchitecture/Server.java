package com.serverarchitecture;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.ServerBuilder;
import org.flywaydb.core.Flyway;

public class Server {
    public static void main(String[] args) throws Exception {
        HikariDataSource dataSource = Database.createDataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();

        ServerClientServiceImpl service = new ServerClientServiceImpl(
                new StudentRepository(dataSource),
                new CourseRepository(dataSource));

        io.grpc.Server server = ServerBuilder.forPort(8081).addService(service).build();

        server.start();
        System.out.println("서버가동ON 아 아 포트번호 : " + server.getPort());

        server.awaitTermination();
    }
}
