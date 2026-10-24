package com.serverarchitecture;

import io.grpc.BindableService;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GrpcServerRunner {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerRunner.class);

    private GrpcServerRunner() {
    }

    public static void run(String name, int port, BindableService service, List<ServerInterceptor> interceptors,
                           AutoCloseable... resources) throws Exception {
        io.grpc.Server server = ServerBuilder.forPort(port)
                .addService(ServerInterceptors.intercept(service, interceptors))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            try {
                server.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (AutoCloseable resource : resources) {
                try {
                    resource.close();
                } catch (Exception e) {
                    log.warn("{} 자원 정리 실패: {}", name, e.getMessage());
                }
            }
        }, name + "-shutdown"));

        server.start();
        log.info("{} 가동 포트 {}", name, server.getPort());
        server.awaitTermination();
    }
}
