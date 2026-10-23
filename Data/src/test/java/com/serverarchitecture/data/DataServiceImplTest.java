package com.serverarchitecture.data;

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
import com.serverarchitecture.data.api.UpsertResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataServiceImplTest {
    @TempDir
    Path directory;

    private Store store;
    private Server server;
    private ManagedChannel channel;
    private DataServiceGrpc.DataServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        store = Store.open(directory, "always");
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new DataServiceImpl(store))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = DataServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        store.close();
    }

    private static Item item(String id, String... keyValues) {
        Item.Builder builder = Item.newBuilder().setId(id);
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.putFields(keyValues[i], keyValues[i + 1]);
        }
        return builder.build();
    }

    private Item create(String collection, Item item) {
        return stub.create(CreateRequest.newBuilder().setCollection(collection).setItem(item).build());
    }

    private UpsertResponse upsert(String collection, Item item, Map<String, String> expected) {
        return stub.upsert(UpsertRequest.newBuilder().setCollection(collection).setItem(item).putAllExpected(expected).build());
    }

    private Item update(String collection, Item item, Map<String, String> expected) {
        return stub.update(UpdateRequest.newBuilder().setCollection(collection).setItem(item).putAllExpected(expected).build());
    }

    private List<String> readAllIds(String collection) {
        return stub.readAll(ReadAllRequest.newBuilder().setCollection(collection).build())
                .getItemsList().stream().map(Item::getId).toList();
    }

    private static Status failure(Executable call) {
        return assertThrows(StatusRuntimeException.class, call).getStatus();
    }

    private static void assertKoreanDescription(Status status) {
        assertTrue(status.getDescription() != null && status.getDescription().matches(".*[가-힣].*"),
                String.valueOf(status.getDescription()));
    }

    @Test
    void createReadAndReadAll() {
        Item stored = create("things", item("b", "k", "v"));
        create("things", item("a"));

        assertEquals(item("b", "k", "v"), stored);
        ReadResponse found = stub.read(ReadRequest.newBuilder().setCollection("things").setId("b").build());
        assertTrue(found.getFound());
        assertEquals("v", found.getItem().getFieldsOrThrow("k"));
        assertEquals(List.of("b", "a"), readAllIds("things"));
    }

    @Test
    void readMissingReturnsNotFoundWithEmptyItem() {
        create("things", item("a"));

        ReadResponse missing = stub.read(ReadRequest.newBuilder().setCollection("things").setId("z").build());
        ReadResponse unknown = stub.read(ReadRequest.newBuilder().setCollection("unknown").setId("a").build());
        ReadResponse empty = stub.read(ReadRequest.newBuilder().setCollection("things").setId("").build());

        for (ReadResponse response : List.of(missing, unknown, empty)) {
            assertFalse(response.getFound());
            assertFalse(response.hasItem());
        }
        assertEquals(List.of(), readAllIds("unknown"));
        assertEquals(0, stub.count(CountRequest.newBuilder().setCollection("unknown").build()).getCount());
    }

    @Test
    void createFailures() {
        create("things", item("a"));

        Status duplicate = failure(() -> create("things", item("a")));
        Status emptyId = failure(() -> create("things", item("")));
        Status noItem = failure(() -> stub.create(CreateRequest.newBuilder().setCollection("things").build()));
        Status badName = failure(() -> create("Bad-Name", item("a")));

        assertEquals(Status.Code.ALREADY_EXISTS, duplicate.getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, emptyId.getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, noItem.getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, badName.getCode());
        for (Status status : List.of(duplicate, emptyId, noItem, badName)) {
            assertKoreanDescription(status);
        }
    }

    @Test
    void invalidCollectionNameIsRejectedOnEveryRpc() {
        String bad = "../etc";
        List<Executable> calls = List.of(
                () -> stub.read(ReadRequest.newBuilder().setCollection(bad).setId("a").build()),
                () -> stub.readAll(ReadAllRequest.newBuilder().setCollection(bad).build()),
                () -> stub.readWhere(ReadWhereRequest.newBuilder().setCollection(bad).setField("f").build()),
                () -> update(bad, item("a"), Map.of()),
                () -> upsert(bad, item("a"), Map.of()),
                () -> stub.delete(DeleteRequest.newBuilder().setCollection(bad).setId("a").build()),
                () -> stub.count(CountRequest.newBuilder().setCollection(bad).build()));
        for (Executable call : calls) {
            Status status = failure(call);
            assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
            assertKoreanDescription(status);
        }
    }

    @Test
    void readWhereFiltersInCreationOrder() {
        create("things", item("a", "group", "x"));
        create("things", item("b"));
        create("things", item("c", "group", "x"));

        List<String> matched = stub.readWhere(ReadWhereRequest.newBuilder()
                .setCollection("things").setField("group").setValue("x").build())
                .getItemsList().stream().map(Item::getId).toList();
        List<String> blank = stub.readWhere(ReadWhereRequest.newBuilder()
                .setCollection("things").setField("group").setValue("").build())
                .getItemsList().stream().map(Item::getId).toList();
        Status noField = failure(() -> stub.readWhere(ReadWhereRequest.newBuilder()
                .setCollection("things").setValue("x").build()));

        assertEquals(List.of("a", "c"), matched);
        assertEquals(List.of("b"), blank);
        assertEquals(Status.Code.INVALID_ARGUMENT, noField.getCode());
        assertKoreanDescription(noField);
    }

    @Test
    void updateStatuses() {
        create("things", item("a", "state", "open"));

        Status missing = failure(() -> update("things", item("z"), Map.of()));
        Status unknownCollection = failure(() -> update("fresh", item("z"), Map.of()));
        Status mismatch = failure(() -> update("things", item("a", "state", "closed"), Map.of("state", "done")));
        Item updated = update("things", item("a", "state", "closed"), Map.of("state", "open"));

        assertEquals(Status.Code.NOT_FOUND, missing.getCode());
        assertEquals(Status.Code.NOT_FOUND, unknownCollection.getCode());
        assertEquals(Status.Code.FAILED_PRECONDITION, mismatch.getCode());
        for (Status status : List.of(missing, unknownCollection, mismatch)) {
            assertKoreanDescription(status);
        }
        assertEquals(item("a", "state", "closed"), updated);
        assertEquals(item("a", "state", "closed"),
                stub.read(ReadRequest.newBuilder().setCollection("things").setId("a").build()).getItem());
    }

    @Test
    void upsertOutcomes() {
        UpsertResponse created = upsert("things", item("a", "v", "1"), Map.of("v", "x"));
        UpsertResponse unchanged = upsert("things", item("a", "v", "2"), Map.of("v", "x"));
        UpsertResponse updated = upsert("things", item("a", "v", "2"), Map.of("v", "1"));
        UpsertResponse unconditional = upsert("things", item("a", "v", "3"), Map.of());
        Status emptyId = failure(() -> upsert("things", item(""), Map.of()));

        assertEquals(UpsertOutcome.CREATED, created.getOutcome());
        assertEquals(item("a", "v", "1"), created.getItem());
        assertEquals(UpsertOutcome.UNCHANGED, unchanged.getOutcome());
        assertEquals(item("a", "v", "1"), unchanged.getItem());
        assertEquals(UpsertOutcome.UPDATED, updated.getOutcome());
        assertEquals(item("a", "v", "2"), updated.getItem());
        assertEquals(UpsertOutcome.UPDATED, unconditional.getOutcome());
        assertEquals(Status.Code.INVALID_ARGUMENT, emptyId.getCode());
    }

    @Test
    void deleteAndCount() {
        create("things", item("a"));
        create("things", item("b"));

        assertTrue(stub.delete(DeleteRequest.newBuilder().setCollection("things").setId("a").build()).getDeleted());
        assertFalse(stub.delete(DeleteRequest.newBuilder().setCollection("things").setId("a").build()).getDeleted());
        assertFalse(stub.delete(DeleteRequest.newBuilder().setCollection("unknown").setId("a").build()).getDeleted());
        assertEquals(1, stub.count(CountRequest.newBuilder().setCollection("things").build()).getCount());
    }

    @Test
    void writesSurviveRestartBehindTheService() throws Exception {
        create("things", item("a", "v", "1"));
        upsert("things", item("b", "v", "2"), Map.of());
        stop();

        start();
        assertEquals(List.of("a", "b"), readAllIds("things"));
    }

    @Test
    void closedStoreAnswersUnavailable() {
        store.close();

        Status status = failure(() -> create("things", item("a")));

        assertEquals(Status.Code.UNAVAILABLE, status.getCode());
        assertKoreanDescription(status);
    }
}
