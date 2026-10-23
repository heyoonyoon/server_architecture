package com.serverarchitecture.client;

import com.serverarchitecture.AuthServiceGrpc;
import com.serverarchitecture.LoginRequest;
import com.serverarchitecture.LoginResponse;
import io.grpc.Channel;

import java.util.concurrent.TimeUnit;

public class AuthClient {
    private final AuthServiceGrpc.AuthServiceBlockingStub stub;

    public AuthClient(Channel channel) {
        this.stub = AuthServiceGrpc.newBlockingStub(channel);
    }

    public LoginResponse login(String userId, String password) {
        return stub.withDeadlineAfter(AcademicClient.DEADLINE_SECONDS, TimeUnit.SECONDS)
                .login(LoginRequest.newBuilder()
                        .setUserId(userId)
                        .setPassword(password)
                        .build());
    }
}
