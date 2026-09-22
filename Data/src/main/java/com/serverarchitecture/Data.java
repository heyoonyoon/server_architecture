package com.serverarchitecture;


import io.grpc.Server;
import io.grpc.ServerBuilder;

public class Data {
    public static void main(String[] args) throws Exception {
        Server data = ServerBuilder.forPort(8080).addService(new DataServerServiceImpl()).build();

        data.start();
        System.out.println("데이터 가동을 시작했습니다 아 아 아 아 포트번호 : " + data.getPort());
        data.awaitTermination();
    }
}
