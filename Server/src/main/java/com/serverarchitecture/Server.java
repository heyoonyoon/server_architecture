package com.serverarchitecture;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;

public class Server {
    public static void main(String[] args) throws Exception {
        io.grpc.Server server = ServerBuilder.forPort(8081).addService(new ServerClientServiceImpl()).build();

        server.start();
        System.out.println("서버가동ON 아 아 포트번호 : " + server.getPort());

        server.awaitTermination();
    }
}
