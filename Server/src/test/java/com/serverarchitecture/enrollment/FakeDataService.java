package com.serverarchitecture.enrollment;

import com.serverarchitecture.data.api.CountRequest;
import com.serverarchitecture.data.api.CountResponse;
import com.serverarchitecture.data.api.DataServiceGrpc;
import com.serverarchitecture.data.api.Item;
import com.serverarchitecture.data.api.ItemList;
import com.serverarchitecture.data.api.ReadRequest;
import com.serverarchitecture.data.api.ReadResponse;
import com.serverarchitecture.data.api.ReadWhereRequest;
import com.serverarchitecture.data.api.UpdateRequest;
import com.serverarchitecture.data.api.UpsertOutcome;
import com.serverarchitecture.data.api.UpsertRequest;
import com.serverarchitecture.data.api.UpsertResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

class FakeDataService extends DataServiceGrpc.DataServiceImplBase {
    private final Map<String, Map<String, Item>> collections = new HashMap<>();
    private final Deque<Status> failures = new ArrayDeque<>();

    synchronized void failNext(Status status, int times) {
        for (int i = 0; i < times; i++) {
            failures.add(status);
        }
    }

    synchronized void put(String collection, Item item) {
        collection(collection).put(item.getId(), item);
    }

    synchronized Item get(String collection, String id) {
        return collection(collection).get(id);
    }

    synchronized int size(String collection) {
        return collection(collection).size();
    }

    private Map<String, Item> collection(String name) {
        return collections.computeIfAbsent(name, key -> new LinkedHashMap<>());
    }

    private boolean failed(StreamObserver<?> observer) {
        Status status = failures.poll();
        if (status == null) {
            return false;
        }
        observer.onError(status.asRuntimeException());
        return true;
    }

    private static boolean matches(Item current, Map<String, String> expected) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(current.getFieldsOrDefault(entry.getKey(), ""))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
        if (failed(responseObserver)) {
            return;
        }
        Item item = collection(request.getCollection()).get(request.getId());
        ReadResponse.Builder builder = ReadResponse.newBuilder().setFound(item != null);
        if (item != null) {
            builder.setItem(item);
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void readWhere(ReadWhereRequest request, StreamObserver<ItemList> responseObserver) {
        if (failed(responseObserver)) {
            return;
        }
        ItemList.Builder builder = ItemList.newBuilder();
        for (Item item : collection(request.getCollection()).values()) {
            if (request.getValue().equals(item.getFieldsOrDefault(request.getField(), ""))) {
                builder.addItems(item);
            }
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void update(UpdateRequest request, StreamObserver<Item> responseObserver) {
        if (failed(responseObserver)) {
            return;
        }
        Map<String, Item> items = collection(request.getCollection());
        Item current = items.get(request.getItem().getId());
        if (current == null) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        if (!matches(current, request.getExpectedMap())) {
            responseObserver.onError(Status.FAILED_PRECONDITION.asRuntimeException());
            return;
        }
        items.put(request.getItem().getId(), request.getItem());
        responseObserver.onNext(request.getItem());
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void upsert(UpsertRequest request, StreamObserver<UpsertResponse> responseObserver) {
        if (failed(responseObserver)) {
            return;
        }
        Map<String, Item> items = collection(request.getCollection());
        Item current = items.get(request.getItem().getId());
        UpsertOutcome outcome;
        if (current == null) {
            outcome = UpsertOutcome.CREATED;
        } else if (matches(current, request.getExpectedMap())) {
            outcome = UpsertOutcome.UPDATED;
        } else {
            responseObserver.onNext(UpsertResponse.newBuilder()
                    .setOutcome(UpsertOutcome.UNCHANGED).setItem(current).build());
            responseObserver.onCompleted();
            return;
        }
        items.put(request.getItem().getId(), request.getItem());
        responseObserver.onNext(UpsertResponse.newBuilder().setOutcome(outcome).setItem(request.getItem()).build());
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void count(CountRequest request, StreamObserver<CountResponse> responseObserver) {
        if (failed(responseObserver)) {
            return;
        }
        responseObserver.onNext(CountResponse.newBuilder().setCount(collection(request.getCollection()).size()).build());
        responseObserver.onCompleted();
    }
}
