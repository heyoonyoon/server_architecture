package com.serverarchitecture;

import com.serverarchitecture.data.api.CountRequest;
import com.serverarchitecture.data.api.CreateRequest;
import com.serverarchitecture.data.api.DataServiceGrpc;
import com.serverarchitecture.data.api.DeleteRequest;
import com.serverarchitecture.data.api.Item;
import com.serverarchitecture.data.api.ReadAllRequest;
import com.serverarchitecture.data.api.ReadRequest;
import com.serverarchitecture.data.api.ReadResponse;
import com.serverarchitecture.data.api.ReadWhereRequest;
import com.serverarchitecture.data.api.UpdateRequest;
import com.serverarchitecture.data.api.UpsertOutcome;
import com.serverarchitecture.data.api.UpsertRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class DataClient implements AutoCloseable {
    private final ManagedChannel channel;
    private final DataServiceGrpc.DataServiceBlockingStub stub;
    private final long deadlineMs;

    public DataClient(ManagedChannel channel, long deadlineMs) {
        this.channel = channel;
        this.stub = DataServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs;
    }

    public static DataClient fromEnv() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(ServiceConfig.DATA_HOST, ServiceConfig.DATA_PORT)
                .usePlaintext()
                .build();
        return new DataClient(channel, ServiceConfig.DATA_DEADLINE_MS);
    }

    public static Item item(String id, Map<String, String> fields) {
        return Item.newBuilder().setId(id).putAllFields(fields).build();
    }

    public Item create(String collection, Item item) {
        return stub().create(CreateRequest.newBuilder().setCollection(collection).setItem(item).build());
    }

    public Optional<Item> read(String collection, String id) {
        ReadResponse response = stub().read(ReadRequest.newBuilder().setCollection(collection).setId(id).build());
        return response.getFound() ? Optional.of(response.getItem()) : Optional.empty();
    }

    public Item require(String collection, String id, String missingMessage) {
        return read(collection, id).orElseThrow(() -> new NullDataException(missingMessage));
    }

    public List<Item> readAll(String collection) {
        return stub().readAll(ReadAllRequest.newBuilder().setCollection(collection).build()).getItemsList();
    }

    public List<Item> readWhere(String collection, String field, String value) {
        return stub().readWhere(ReadWhereRequest.newBuilder()
                .setCollection(collection)
                .setField(field)
                .setValue(value)
                .build()).getItemsList();
    }

    public Item update(String collection, Item item) {
        return update(collection, item, Map.of());
    }

    public Item update(String collection, Item item, Map<String, String> expected) {
        return stub().update(UpdateRequest.newBuilder()
                .setCollection(collection)
                .setItem(item)
                .putAllExpected(expected)
                .build());
    }

    public UpsertOutcome upsert(String collection, Item item, Map<String, String> expected) {
        return stub().upsert(UpsertRequest.newBuilder()
                .setCollection(collection)
                .setItem(item)
                .putAllExpected(expected)
                .build()).getOutcome();
    }

    public boolean delete(String collection, String id) {
        return stub().delete(DeleteRequest.newBuilder().setCollection(collection).setId(id).build()).getDeleted();
    }

    public long count(String collection) {
        return stub().count(CountRequest.newBuilder().setCollection(collection).build()).getCount();
    }

    @Override
    public void close() throws InterruptedException {
        channel.shutdown();
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow();
        }
    }

    private DataServiceGrpc.DataServiceBlockingStub stub() {
        return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }
}
