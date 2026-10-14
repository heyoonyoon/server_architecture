package com.serverarchitecture;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class AccessLogInterceptor implements ServerInterceptor {
    private static final Logger log = LoggerFactory.getLogger("access");
    private static final Map<String, String> COMMANDS = Map.of(
            "LoginController", "LOGIN",
            "GetStudentListController", "LIST_STUDENTS",
            "GetCourseListController", "LIST_COURSES",
            "AddStudentController", "ADD_STUDENT",
            "DeleteStudentController", "DELETE_STUDENT",
            "AddCourseController", "ADD_COURSE",
            "DeleteCourseController", "DELETE_COURSE");

    private final JwtProvider jwtProvider;

    public AccessLogInterceptor(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        AuthenticatedUser user = jwtProvider.verify(AuthInterceptor.bearerToken(headers));
        AccessLogCall<ReqT, RespT> accessLogCall = new AccessLogCall<>(call, user == null ? "-" : user.userId());

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(next.startCall(accessLogCall, headers)) {
            @Override
            public void onMessage(ReqT message) {
                if (message instanceof LoginControllerRequest request) {
                    accessLogCall.userId = sanitize(request.getUserId());
                }
                super.onMessage(message);
            }
        };
    }

    private static String sanitize(String userId) {
        String value = userId.replaceAll("[^A-Za-z0-9_.@-]", "_");
        if (value.isEmpty()) {
            return "-";
        }
        return value.length() > 20 ? value.substring(0, 20) : value;
    }

    private static class AccessLogCall<ReqT, RespT> extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {
        private final long start = System.nanoTime();
        private final String command;
        private volatile String userId;
        private volatile boolean loginFailed;

        AccessLogCall(ServerCall<ReqT, RespT> call, String userId) {
            super(call);
            String methodName = call.getMethodDescriptor().getBareMethodName();
            this.command = COMMANDS.getOrDefault(methodName, methodName);
            this.userId = userId;
        }

        @Override
        public void sendMessage(RespT message) {
            if (message instanceof LoginControllerResponse response && !response.getSuccess()) {
                loginFailed = true;
            }
            super.sendMessage(message);
        }

        @Override
        public void close(Status status, Metadata trailers) {
            String result = status.isOk() && loginFailed ? "LOGIN_FAILED" : status.getCode().name();
            log.info(String.format("user=%-10s | command=%-14s | result=%-17s | %5dms",
                    userId, command, result, (System.nanoTime() - start) / 1_000_000));
            super.close(status, trailers);
        }
    }
}
