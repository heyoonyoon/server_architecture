package com.serverarchitecture.data;

import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class DataServer {
    private static final Logger log = LoggerFactory.getLogger(DataServer.class);

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("DATA_PORT", "9200"));
        Path directory = Path.of(env("DATA_DIR", "data-store"));
        String fsync = env("DATA_FSYNC", "always");

        Store store = Store.open(directory, fsync);
        io.grpc.Server server = ServerBuilder.forPort(port)
                .addService(new DataServiceImpl(store))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            try {
                server.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            store.close();
        }, "data-server-shutdown"));

        server.start();
        log.info("Data 서버 가동 포트 {} 디렉터리 {} fsync {}", server.getPort(), directory.toAbsolutePath(), fsync);
        server.awaitTermination();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
