package com.serverarchitecture;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;
import java.util.function.Supplier;

public final class GrpcResponder {
    private static final Logger log = LoggerFactory.getLogger(GrpcResponder.class);

    private GrpcResponder() {
    }

    public static <T> void respond(StreamObserver<T> responseObserver, Supplier<T> action) {
        respond(responseObserver, action, GrpcResponder::toStatus);
    }

    public static <T> void respond(StreamObserver<T> responseObserver, Supplier<T> action,
                                   Function<RuntimeException, StatusRuntimeException> errors) {
        T response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            responseObserver.onError(errors.apply(e));
            return;
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    public static StatusRuntimeException toStatus(RuntimeException e) {
        if (e instanceof CustomException custom) {
            return custom.toStatus().asRuntimeException();
        }
        if (e instanceof StatusRuntimeException status) {
            return dataError(status);
        }
        log.error("요청 처리 실패", e);
        return Status.INTERNAL
                .withDescription("서버 내부 오류가 발생했습니다.")
                .withCause(e)
                .asRuntimeException();
    }

    private static StatusRuntimeException dataError(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
            log.warn("데이터 서버 호출 실패: {}", e.getStatus());
            return Status.UNAVAILABLE.withDescription("데이터 서버에 연결할 수 없습니다.").withCause(e).asRuntimeException();
        }
        log.error("데이터 서버 오류", e);
        return Status.INTERNAL.withDescription("데이터 처리 중 오류가 발생했습니다.").withCause(e).asRuntimeException();
    }
}
