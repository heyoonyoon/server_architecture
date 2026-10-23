package com.serverarchitecture.auth;

import com.serverarchitecture.AccessLogInterceptor;
import com.serverarchitecture.AuthInterceptor;
import com.serverarchitecture.AuthServiceGrpc;
import com.serverarchitecture.AuthenticatedUser;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.JwtProvider;
import com.serverarchitecture.LoginRequest;
import com.serverarchitecture.LoginResponse;
import com.serverarchitecture.Role;
import com.serverarchitecture.SeedLoader;
import com.serverarchitecture.UserRepository;
import com.serverarchitecture.academic.FakeDataService;
import io.grpc.ForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthServiceImplTest {
    private final JwtProvider jwtProvider = new JwtProvider("auth-service-test-secret-0123456789abcdef", Duration.ofHours(1));
    private final List<Status.Code> closedWith = new CopyOnWriteArrayList<Status.Code>();
    private FakeDataService fake;
    private Server server;
    private ManagedChannel channel;
    private AuthServiceGrpc.AuthServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeDataService();
        SeedLoader.seedIfEmpty(fake.client(), DataCollections.USERS);
        AuthServiceImpl service = new AuthServiceImpl(new UserRepository(fake.client()), jwtProvider);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service,
                        new AuthInterceptor(jwtProvider, Map.of(),
                                Set.of(AuthServiceGrpc.getLoginMethod().getFullMethodName())),
                        new AccessLogInterceptor(jwtProvider),
                        new StatusRecorder()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = AuthServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        fake.close();
    }

    @Test
    void presenterLogsInAsStudent() {
        LoginResponse response = login("60220435", "60220435");

        assertEquals("60220435", response.getUserId());
        assertEquals("Heeyoon", response.getName());
        assertEquals(Role.STUDENT, response.getRole());
        assertEquals(new AuthenticatedUser("60220435", Role.STUDENT), jwtProvider.verify(response.getToken()));
        assertEquals(List.of(Status.Code.OK), closedWith);
    }

    @Test
    void everyProfessorLogsInWithOwnId() {
        for (String id : List.of("P001", "P002", "P003", "P004", "P005")) {
            LoginResponse response = login(id, id);
            assertEquals(Role.PROFESSOR, response.getRole());
            assertEquals(new AuthenticatedUser(id, Role.PROFESSOR), jwtProvider.verify(response.getToken()));
        }
    }

    @Test
    void wrongPasswordUnknownUserAndBlankIdFailTheSameWay() {
        assertLoginFails("60220435", "wrong");
        assertLoginFails("99999999", "99999999");
        assertLoginFails("", "");
        assertEquals(List.of(Status.Code.UNAUTHENTICATED, Status.Code.UNAUTHENTICATED, Status.Code.UNAUTHENTICATED),
                closedWith);
    }

    @Test
    void dataOutageIsUnavailable() {
        fake.stop();

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> login("60220435", "60220435"));
        assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
        assertEquals(List.of(Status.Code.UNAVAILABLE), closedWith);
    }

    private LoginResponse login(String userId, String password) {
        return stub.login(LoginRequest.newBuilder().setUserId(userId).setPassword(password).build());
    }

    private void assertLoginFails(String userId, String password) {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> login(userId, password));
        assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
        assertEquals("아이디 또는 비밀번호가 올바르지 않습니다.", e.getStatus().getDescription());
    }

    private class StatusRecorder implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                @Override
                public void close(Status status, Metadata trailers) {
                    closedWith.add(status.getCode());
                    super.close(status, trailers);
                }
            }, headers);
        }
    }
}
