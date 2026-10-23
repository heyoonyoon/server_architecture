package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;
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
import com.serverarchitecture.data.api.UpsertOutcome;
import com.serverarchitecture.data.api.UpsertRequest;
import com.serverarchitecture.data.api.UpsertResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class FakeDataService extends DataServiceGrpc.DataServiceImplBase implements AutoCloseable {
    private final Map<String, LinkedHashMap<String, Item>> collections = new LinkedHashMap<String, LinkedHashMap<String, Item>>();
    private final Server server;
    private final ManagedChannel channel;
    private final DataClient client;

    public FakeDataService() throws IOException {
        String name = InProcessServerBuilder.generateName();
        this.server = InProcessServerBuilder.forName(name).directExecutor().addService(this).build().start();
        this.channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        this.client = new DataClient(channel, 3000);
    }

    public DataClient client() {
        return client;
    }

    public void stop() {
        server.shutdownNow();
    }

    @Override
    public void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Override
    public synchronized void create(CreateRequest request, StreamObserver<Item> responseObserver) {
        respond(responseObserver, () -> {
            Item item = request.getItem();
            if (item.getId().isEmpty()) {
                throw Status.INVALID_ARGUMENT.withDescription("id").asRuntimeException();
            }
            LinkedHashMap<String, Item> items = collection(request.getCollection());
            if (items.containsKey(item.getId())) {
                throw Status.ALREADY_EXISTS.withDescription(item.getId()).asRuntimeException();
            }
            items.put(item.getId(), item);
            return item;
        });
    }

    @Override
    public synchronized void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
        respond(responseObserver, () -> {
            Item item = existing(request.getCollection()).get(request.getId());
            return item == null ? ReadResponse.newBuilder().setFound(false).build()
                    : ReadResponse.newBuilder().setFound(true).setItem(item).build();
        });
    }

    @Override
    public synchronized void readAll(ReadAllRequest request, StreamObserver<ItemList> responseObserver) {
        respond(responseObserver, () -> ItemList.newBuilder()
                .addAllItems(existing(request.getCollection()).values())
                .build());
    }

    @Override
    public synchronized void readWhere(ReadWhereRequest request, StreamObserver<ItemList> responseObserver) {
        respond(responseObserver, () -> ItemList.newBuilder()
                .addAllItems(existing(request.getCollection()).values().stream()
                        .filter(item -> item.getFieldsOrDefault(request.getField(), "").equals(request.getValue()))
                        .toList())
                .build());
    }

    @Override
    public synchronized void update(UpdateRequest request, StreamObserver<Item> responseObserver) {
        respond(responseObserver, () -> {
            LinkedHashMap<String, Item> items = collection(request.getCollection());
            Item current = items.get(request.getItem().getId());
            if (current == null) {
                throw Status.NOT_FOUND.asRuntimeException();
            }
            if (!matches(current, request.getExpectedMap())) {
                throw Status.FAILED_PRECONDITION.asRuntimeException();
            }
            items.put(current.getId(), request.getItem());
            return request.getItem();
        });
    }

    @Override
    public synchronized void upsert(UpsertRequest request, StreamObserver<UpsertResponse> responseObserver) {
        respond(responseObserver, () -> {
            LinkedHashMap<String, Item> items = collection(request.getCollection());
            Item item = request.getItem();
            Item current = items.get(item.getId());
            if (current == null) {
                items.put(item.getId(), item);
                return UpsertResponse.newBuilder().setOutcome(UpsertOutcome.CREATED).setItem(item).build();
            }
            if (!matches(current, request.getExpectedMap())) {
                return UpsertResponse.newBuilder().setOutcome(UpsertOutcome.UNCHANGED).setItem(current).build();
            }
            items.put(item.getId(), item);
            return UpsertResponse.newBuilder().setOutcome(UpsertOutcome.UPDATED).setItem(item).build();
        });
    }

    @Override
    public synchronized void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
        respond(responseObserver, () -> DeleteResponse.newBuilder()
                .setDeleted(existing(request.getCollection()).remove(request.getId()) != null)
                .build());
    }

    @Override
    public synchronized void count(CountRequest request, StreamObserver<CountResponse> responseObserver) {
        respond(responseObserver, () -> CountResponse.newBuilder()
                .setCount(existing(request.getCollection()).size())
                .build());
    }

    private LinkedHashMap<String, Item> collection(String name) {
        validate(name);
        return collections.computeIfAbsent(name, key -> new LinkedHashMap<String, Item>());
    }

    private Map<String, Item> existing(String name) {
        validate(name);
        return collections.getOrDefault(name, new LinkedHashMap<String, Item>());
    }

    private static void validate(String name) {
        if (!name.matches("[a-z0-9_]+")) {
            throw Status.INVALID_ARGUMENT.withDescription(name).asRuntimeException();
        }
    }

    private static boolean matches(Item current, Map<String, String> expected) {
        return expected.entrySet().stream()
                .allMatch(entry -> current.getFieldsOrDefault(entry.getKey(), "").equals(entry.getValue()));
    }

    private static <T> void respond(StreamObserver<T> responseObserver, Supplier<T> action) {
        T response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            responseObserver.onError(e);
            return;
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
