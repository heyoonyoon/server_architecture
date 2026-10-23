package com.serverarchitecture.auth;

import com.serverarchitecture.AuthServiceGrpc;
import com.serverarchitecture.JwtProvider;

public class AuthServiceImpl extends AuthServiceGrpc.AuthServiceImplBase {
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    public AuthServiceImpl(UserRepository userRepository, JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
    }
}
