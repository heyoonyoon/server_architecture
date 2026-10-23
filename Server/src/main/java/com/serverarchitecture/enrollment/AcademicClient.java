package com.serverarchitecture.enrollment;

import com.serverarchitecture.AcademicServiceGrpc;
import com.serverarchitecture.BearerCredentials;
import com.serverarchitecture.CustomException;
import com.serverarchitecture.GetCatalogRequest;
import com.serverarchitecture.GetStudentRequest;
import com.serverarchitecture.JwtProvider;
import com.serverarchitecture.NullDataException;
import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.ServiceConfig;
import com.serverarchitecture.StudentInfo;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class AcademicClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AcademicClient.class);
    private static final String SERVICE_NAME = "enrollment-service";

    private final ManagedChannel channel;
    private final AcademicServiceGrpc.AcademicServiceBlockingStub stub;
    private final long deadlineMs;

    public AcademicClient(ManagedChannel channel, JwtProvider jwtProvider, long deadlineMs) {
        this.channel = channel;
        this.stub = AcademicServiceGrpc.newBlockingStub(channel)
                .withCallCredentials(new BearerCredentials(() -> jwtProvider.issueService(SERVICE_NAME)));
        this.deadlineMs = deadlineMs;
    }

    public static AcademicClient fromEnv(JwtProvider jwtProvider) {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(ServiceConfig.ACADEMIC_HOST, ServiceConfig.ACADEMIC_PORT)
                .usePlaintext()
                .build();
        return new AcademicClient(channel, jwtProvider, ServiceConfig.ACADEMIC_DEADLINE_MS);
    }

    public StudentInfo getStudent(String studentId) {
        try {
            return stub().getStudent(GetStudentRequest.newBuilder().setStudentId(studentId).build());
        } catch (StatusRuntimeException e) {
            throw translate(e);
        }
    }

    public List<OfferingInfo> catalog(String semester) {
        try {
            return stub().getCatalog(GetCatalogRequest.newBuilder().setSemester(semester).build()).getOfferingsList();
        } catch (StatusRuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public void close() throws InterruptedException {
        channel.shutdown();
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow();
        }
    }

    private CustomException translate(StatusRuntimeException e) {
        Status status = e.getStatus();
        if (status.getCode() == Status.Code.NOT_FOUND) {
            return new NullDataException(status.getDescription());
        }
        log.warn("학사 서비스 호출 실패 code={} description={}", status.getCode(), status.getDescription());
        return new CustomException(Status.Code.UNAVAILABLE, "학사 서비스에 연결할 수 없습니다. 잠시 후 다시 시도하세요.");
    }

    private AcademicServiceGrpc.AcademicServiceBlockingStub stub() {
        return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }
}
