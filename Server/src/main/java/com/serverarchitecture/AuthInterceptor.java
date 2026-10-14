package com.serverarchitecture;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.Map;
import java.util.Set;

public class AuthInterceptor implements ServerInterceptor {
    public static final Context.Key<AuthenticatedUser> USER = Context.key("user");

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER = "Bearer ";
    private static final String LOGIN = ServerClientServiceGrpc.getLoginControllerMethod().getFullMethodName();
    private static final Map<String, Set<Role>> PERMISSIONS = Map.ofEntries(
            Map.entry(ServerClientServiceGrpc.getGetStudentListControllerMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(ServerClientServiceGrpc.getGetCourseListControllerMethod().getFullMethodName(), Set.of(Role.PROFESSOR, Role.STUDENT)),
            Map.entry(ServerClientServiceGrpc.getAddStudentControllerMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(ServerClientServiceGrpc.getDeleteStudentControllerMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(ServerClientServiceGrpc.getAddCourseControllerMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(ServerClientServiceGrpc.getDeleteCourseControllerMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(EnrollmentServiceGrpc.getEnterQueueMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getGetQueueStatusMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getListOfferingsMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getEnrollMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getCancelEnrollmentMethod().getFullMethodName(), Set.of(Role.STUDENT)),
            Map.entry(EnrollmentServiceGrpc.getListMyEnrollmentsMethod().getFullMethodName(), Set.of(Role.STUDENT)));

    private final JwtProvider jwtProvider;

    public AuthInterceptor(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    public static String bearerToken(Metadata headers) {
        String value = headers.get(AUTHORIZATION);
        return value != null && value.startsWith(BEARER) ? value.substring(BEARER.length()) : null;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();
        if (method.equals(LOGIN)) {
            return next.startCall(call, headers);
        }

        AuthenticatedUser user = jwtProvider.verify(bearerToken(headers));
        if (user == null) {
            return reject(call, Status.UNAUTHENTICATED.withDescription("로그인이 필요합니다."));
        }
        if (!PERMISSIONS.getOrDefault(method, Set.of()).contains(user.role())) {
            return reject(call, Status.PERMISSION_DENIED.withDescription("권한이 없습니다."));
        }

        Context context = Context.current().withValue(USER, user);
        return Contexts.interceptCall(context, call, headers, next);
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<ReqT>() {
        };
    }
}
