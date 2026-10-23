package com.serverarchitecture.auth;

import com.serverarchitecture.AuthServiceGrpc;
import com.serverarchitecture.CustomException;
import com.serverarchitecture.GrpcResponder;
import com.serverarchitecture.JwtProvider;
import com.serverarchitecture.LoginRequest;
import com.serverarchitecture.LoginResponse;
import com.serverarchitecture.PasswordHasher;
import com.serverarchitecture.Role;
import com.serverarchitecture.User;
import com.serverarchitecture.UserRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Optional;

public class AuthServiceImpl extends AuthServiceGrpc.AuthServiceImplBase {
    private static final String DUMMY_PASSWORD_HASH = PasswordHasher.hash("dummy");
    private static final String LOGIN_FAILED = "아이디 또는 비밀번호가 올바르지 않습니다.";

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    public AuthServiceImpl(UserRepository userRepository, JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
    }

    @Override
    public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
        GrpcResponder.respond(responseObserver, () -> {
            User user = authenticate(request.getUserId(), request.getPassword());
            return LoginResponse.newBuilder()
                    .setToken(jwtProvider.issue(user))
                    .setUserId(user.getUserId())
                    .setName(user.getName())
                    .setRole(user.getRole())
                    .build();
        });
    }

    private User authenticate(String userId, String password) {
        Optional<User> user = userId.isBlank() ? Optional.empty() : userRepository.findById(userId);
        if (user.isEmpty()) {
            PasswordHasher.matches(password, DUMMY_PASSWORD_HASH);
            throw new CustomException(Status.Code.UNAUTHENTICATED, LOGIN_FAILED);
        }
        if (!PasswordHasher.matches(password, user.get().getPasswordHash()) || user.get().getRole() == Role.SERVICE) {
            throw new CustomException(Status.Code.UNAUTHENTICATED, LOGIN_FAILED);
        }
        return user.get();
    }
}
