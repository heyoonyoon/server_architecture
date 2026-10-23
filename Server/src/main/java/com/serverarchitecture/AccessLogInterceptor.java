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
    private static final Map<String, String> COMMANDS = Map.ofEntries(
            Map.entry("Login", "LOGIN"),
            Map.entry("ListStudents", "LIST_STUDENTS"),
            Map.entry("ListCourses", "LIST_COURSES"),
            Map.entry("GetStudent", "GET_STUDENT"),
            Map.entry("AddStudent", "ADD_STUDENT"),
            Map.entry("DeleteStudent", "DELETE_STUDENT"),
            Map.entry("AddCourse", "ADD_COURSE"),
            Map.entry("DeleteCourse", "DELETE_COURSE"),
            Map.entry("ListCoursesByStudent", "COURSES_BY_STUDENT"),
            Map.entry("ListStudentsByCourse", "STUDENTS_BY_COURSE"),
            Map.entry("ListCompletedCourses", "COMPLETED_COURSES"),
            Map.entry("GetCatalog", "GET_CATALOG"),
            Map.entry("EnterQueue", "ENTER_QUEUE"),
            Map.entry("GetQueueStatus", "QUEUE_STATUS"),
            Map.entry("WatchQueue", "WATCH_QUEUE"),
            Map.entry("LeaveQueue", "LEAVE_QUEUE"),
            Map.entry("GetSchedule", "GET_SCHEDULE"),
            Map.entry("ListOfferings", "LIST_OFFERINGS"),
            Map.entry("Enroll", "ENROLL"),
            Map.entry("CancelEnrollment", "CANCEL_ENROLLMENT"),
            Map.entry("ListMyEnrollments", "LIST_MY_ENROLLMENTS"));

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
                if (message instanceof LoginRequest request) {
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

        AccessLogCall(ServerCall<ReqT, RespT> call, String userId) {
            super(call);
            String methodName = call.getMethodDescriptor().getBareMethodName();
            this.command = COMMANDS.getOrDefault(methodName, methodName);
            this.userId = userId;
        }

        @Override
        public void close(Status status, Metadata trailers) {
            String result = status.getCode().name();
            log.info(String.format("user=%-10s | command=%-19s | result=%-17s | %5dms",
                    userId, command, result, (System.nanoTime() - start) / 1_000_000));
            super.close(status, trailers);
        }
    }
}
