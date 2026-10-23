package com.serverarchitecture.auth;

import com.serverarchitecture.AccessLogInterceptor;
import com.serverarchitecture.AuthInterceptor;
import com.serverarchitecture.AuthServiceGrpc;
import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.GrpcServerRunner;
import com.serverarchitecture.JwtProvider;
import com.serverarchitecture.ServiceConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class AuthServer {
    public static void main(String[] args) throws Exception {
        JwtProvider jwtProvider = JwtProvider.fromEnv();
        DataClient data = DataClient.fromEnv();
        if (ServiceConfig.SEED_ON_START) {
            StartupSeeder.seed(data, DataCollections.USERS);
        }
        AuthServiceImpl service = new AuthServiceImpl(new UserRepository(data), jwtProvider);

        GrpcServerRunner.run("auth", ServiceConfig.AUTH_PORT, service,
                List.of(new AuthInterceptor(jwtProvider, Map.of(),
                                Set.of(AuthServiceGrpc.getLoginMethod().getFullMethodName())),
                        new AccessLogInterceptor(jwtProvider)),
                data);
    }
}
