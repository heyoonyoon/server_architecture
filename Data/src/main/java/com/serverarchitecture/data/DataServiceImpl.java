package com.serverarchitecture.data;

import com.serverarchitecture.data.api.CountRequest;
import com.serverarchitecture.data.api.CountResponse;
import com.serverarchitecture.data.api.CreateRequest;
import com.serverarchitecture.data.api.DataServiceGrpc;
import com.serverarchitecture.data.api.DeleteRequest;
import com.serverarchitecture.data.api.DeleteResponse;
import com.serverarchitecture.data.api.Item;
import com.serverarchitecture.data.api.ItemList;
import com.serverarchitecture.data.api.ReadAllRequest;
import com.serverarchitecture.data.api.ReadRequest;
import com.serverarchitecture.data.api.ReadResponse;
import com.serverarchitecture.data.api.ReadWhereRequest;
import com.serverarchitecture.data.api.UpdateRequest;
import com.serverarchitecture.data.api.UpsertRequest;
import com.serverarchitecture.data.api.UpsertResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.Optional;

public class DataServiceImpl extends DataServiceGrpc.DataServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(DataServiceImpl.class);

    private final Store store;

    public DataServiceImpl(Store store) {
        this.store = store;
    }

    @Override
    public void create(CreateRequest request, StreamObserver<Item> responseObserver) {
        respond(responseObserver, () -> store.create(request.getCollection(), request.getItem()));
    }

    @Override
    public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
        respond(responseObserver, () -> {
            Optional<Item> item = store.read(request.getCollection(), request.getId());
            ReadResponse.Builder builder = ReadResponse.newBuilder().setFound(item.isPresent());
            item.ifPresent(builder::setItem);
            return builder.build();
        });
    }

    @Override
    public void readAll(ReadAllRequest request, StreamObserver<ItemList> responseObserver) {
        respond(responseObserver, () -> ItemList.newBuilder()
                .addAllItems(store.readAll(request.getCollection()))
                .build());
    }

    @Override
    public void readWhere(ReadWhereRequest request, StreamObserver<ItemList> responseObserver) {
        respond(responseObserver, () -> ItemList.newBuilder()
                .addAllItems(store.readWhere(request.getCollection(), request.getField(), request.getValue()))
                .build());
    }

    @Override
    public void update(UpdateRequest request, StreamObserver<Item> responseObserver) {
        respond(responseObserver, () -> store.update(request.getCollection(), request.getItem(), request.getExpectedMap()));
    }

    @Override
    public void upsert(UpsertRequest request, StreamObserver<UpsertResponse> responseObserver) {
        respond(responseObserver, () -> store.upsert(request.getCollection(), request.getItem(), request.getExpectedMap()));
    }

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
        respond(responseObserver, () -> DeleteResponse.newBuilder()
                .setDeleted(store.delete(request.getCollection(), request.getId()))
                .build());
    }

    @Override
    public void count(CountRequest request, StreamObserver<CountResponse> responseObserver) {
        respond(responseObserver, () -> CountResponse.newBuilder()
                .setCount(store.count(request.getCollection()))
                .build());
    }

    private <T> void respond(StreamObserver<T> responseObserver, Action<T> action) {
        T response;
        try {
            response = action.run();
        } catch (StoreException e) {
            responseObserver.onError(e.toStatus().asRuntimeException());
            return;
        } catch (UncheckedIOException e) {
            log.error("Data 저장소 기록 실패", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("데이터를 파일에 기록하지 못했습니다.")
                    .withCause(e)
                    .asRuntimeException());
            return;
        } catch (RuntimeException e) {
            log.error("Data 요청 처리 실패", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Data 서버 내부 오류가 발생했습니다.")
                    .withCause(e)
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @FunctionalInterface
    private interface Action<T> {
        T run();
    }
}
