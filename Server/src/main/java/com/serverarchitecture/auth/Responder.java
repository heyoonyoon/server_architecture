package com.serverarchitecture.auth;

import com.serverarchitecture.CustomException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public final class Responder {
    private static final Logger log = LoggerFactory.getLogger(Responder.class);

    private Responder() {
    }

    public static <T> void respond(StreamObserver<T> responseObserver, Supplier<T> action) {
        T response;
        try {
            response = action.get();
        } catch (CustomException e) {
            responseObserver.onError(e.toStatus().asRuntimeException());
            return;
        } catch (StatusRuntimeException e) {
            responseObserver.onError(dataError(e));
            return;
        } catch (RuntimeException e) {
            log.error("요청 처리 실패", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("서버 내부 오류가 발생했습니다.")
                    .withCause(e)
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
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
