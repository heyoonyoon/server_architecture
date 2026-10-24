package com.serverarchitecture.data;

import com.serverarchitecture.data.api.Item;
import com.serverarchitecture.data.api.UpsertOutcome;
import com.serverarchitecture.data.api.UpsertResponse;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {
    @TempDir
    Path directory;

    private static Item item(String id, String... keyValues) {
        Item.Builder builder = Item.newBuilder().setId(id);
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.putFields(keyValues[i], keyValues[i + 1]);
        }
        return builder.build();
    }

    private static List<String> ids(List<Item> items) {
        return items.stream().map(Item::getId).toList();
    }

    private static Status.Code codeOf(Runnable action) {
        return assertThrows(StoreException.class, action::run).code();
    }

    private Store open() {
        return Store.open(directory, FsyncPolicy.ALWAYS);
    }

    private List<String> lines(String collection) throws IOException {
        return Files.readAllLines(directory.resolve(collection + ".txt"), StandardCharsets.US_ASCII);
    }

    @Test
    void createKeepsInsertionOrderAndRejectsDuplicates() {
        try (Store store = open()) {
            store.create("things", item("c", "k", "1"));
            store.create("things", item("a", "k", "2"));
            store.create("things", item("b", "k", "1"));

            assertEquals(List.of("c", "a", "b"), ids(store.readAll("things")));
            assertEquals(Status.Code.ALREADY_EXISTS, codeOf(() -> store.create("things", item("a"))));
            assertEquals("2", store.read("things", "a").orElseThrow().getFieldsOrThrow("k"));
            assertEquals(3, store.count("things"));
        }
    }

    @Test
    void rejectsInvalidInput() {
        try (Store store = open()) {
            assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> store.create("things", item(""))));
            assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> store.create("Things", item("a"))));
            assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> store.create("../x", item("a"))));
            assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> store.create("", item("a"))));
            assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> store.read("a-b", "a")));
            assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> store.readWhere("things", "", "x")));
            assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> store.update("things", item(""), Map.of())));
            assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> store.upsert("things", item(""), Map.of())));
        }
    }

    @Test
    void unknownCollectionReadsAsEmptyWithoutCreatingFile() {
        try (Store store = open()) {
            assertTrue(store.read("nothing", "a").isEmpty());
            assertEquals(List.of(), store.readAll("nothing"));
            assertEquals(List.of(), store.readWhere("nothing", "k", ""));
            assertEquals(0, store.count("nothing"));
            assertFalse(store.delete("nothing", "a"));
            assertEquals(Status.Code.NOT_FOUND, codeOf(() -> store.update("nothing", item("a"), Map.of())));
        }
        assertFalse(Files.exists(directory.resolve("nothing.txt")));
    }

    @Test
    void readWhereTreatsMissingFieldAsEmpty() {
        try (Store store = open()) {
            store.create("things", item("a", "color", "red"));
            store.create("things", item("b"));
            store.create("things", item("c", "color", ""));
            store.create("things", item("d", "color", "red"));

            assertEquals(List.of("a", "d"), ids(store.readWhere("things", "color", "red")));
            assertEquals(List.of("b", "c"), ids(store.readWhere("things", "color", "")));
        }
    }

    @Test
    void updateChecksExistenceAndExpectedFields() {
        try (Store store = open()) {
            store.create("things", item("a", "state", "open", "n", "1"));

            assertEquals(Status.Code.NOT_FOUND, codeOf(() -> store.update("things", item("z"), Map.of())));
            assertEquals(Status.Code.FAILED_PRECONDITION,
                    codeOf(() -> store.update("things", item("a", "state", "x"), Map.of("state", "closed"))));
            assertEquals(Status.Code.FAILED_PRECONDITION,
                    codeOf(() -> store.update("things", item("a", "state", "x"), Map.of("other", "y"))));

            Item updated = store.update("things", item("a", "state", "closed"), Map.of("state", "open", "other", ""));
            assertEquals(Map.of("state", "closed"), updated.getFieldsMap());
            assertEquals(Map.of("state", "closed"), store.read("things", "a").orElseThrow().getFieldsMap());

            store.update("things", item("a", "state", "again"), Map.of());
            assertEquals("again", store.read("things", "a").orElseThrow().getFieldsOrThrow("state"));
        }
    }

    @Test
    void upsertReportsOutcome() {
        try (Store store = open()) {
            UpsertResponse created = store.upsert("things", item("a", "v", "1"), Map.of("v", "ignored"));
            assertEquals(UpsertOutcome.CREATED, created.getOutcome());

            UpsertResponse updated = store.upsert("things", item("a", "v", "2"), Map.of("v", "1"));
            assertEquals(UpsertOutcome.UPDATED, updated.getOutcome());
            assertEquals("2", updated.getItem().getFieldsOrThrow("v"));

            UpsertResponse unchanged = store.upsert("things", item("a", "v", "3"), Map.of("v", "1"));
            assertEquals(UpsertOutcome.UNCHANGED, unchanged.getOutcome());
            assertEquals("2", unchanged.getItem().getFieldsOrThrow("v"));

            assertEquals(UpsertOutcome.UPDATED, store.upsert("things", item("a", "v", "4"), Map.of()).getOutcome());
            assertEquals("4", store.read("things", "a").orElseThrow().getFieldsOrThrow("v"));
        }
    }

    @Test
    void replaysAfterRestartInCreationOrder() {
        String awkward = "tab\there\nnew line \"quote\" 한글 \\ end";
        try (Store store = open()) {
            store.create("things", item("a", "v", "1"));
            store.create("things", item("b", "v", awkward));
            store.upsert("things", item("c", "v", "3"), Map.of());
            store.update("things", item("a", "v", "1b"), Map.of("v", "1"));
            store.delete("things", "b");
            store.create("things", item("b", "v", "again"));
            store.upsert("things", item("c", "v", "3b"), Map.of());
            store.create("others", item("x\ty", "k", awkward));
        }

        try (Store store = open()) {
            assertEquals(List.of("a", "c", "b"), ids(store.readAll("things")));
            assertEquals("1b", store.read("things", "a").orElseThrow().getFieldsOrThrow("v"));
            assertEquals("again", store.read("things", "b").orElseThrow().getFieldsOrThrow("v"));
            assertEquals("3b", store.read("things", "c").orElseThrow().getFieldsOrThrow("v"));
            assertEquals(awkward, store.read("others", "x\ty").orElseThrow().getFieldsOrThrow("k"));
            store.create("things", item("d"));
        }

        try (Store store = open()) {
            assertEquals(List.of("a", "c", "b", "d"), ids(store.readAll("things")));
        }
    }

    @Test
    void compactsLogOnStartup() throws IOException {
        try (Store store = open()) {
            for (int i = 0; i < 50; i++) {
                store.upsert("things", item("a", "v", Integer.toString(i)), Map.of());
            }
            store.create("things", item("b"));
            store.create("things", item("c"));
            store.delete("things", "b");
        }
        assertEquals(53, lines("things").size());

        try (Store store = open()) {
            assertEquals(List.of("a", "c"), ids(store.readAll("things")));
            assertEquals("49", store.read("things", "a").orElseThrow().getFieldsOrThrow("v"));
            store.create("things", item("e"));
        }
        assertEquals(3, lines("things").size());
        assertFalse(Files.exists(directory.resolve("things.txt.tmp")));

        try (Store store = open()) {
            assertEquals(List.of("a", "c", "e"), ids(store.readAll("things")));
        }
    }

    @Test
    void skipsUnterminatedLastRecord() throws IOException {
        try (Store store = open()) {
            store.create("things", item("a"));
            store.create("things", item("b"));
        }
        Path log = directory.resolve("things.txt");
        byte[] full = LogRecord.put(item("c", "v", "lost")).encode();
        Files.write(log, Arrays.copyOf(full, full.length - 5), StandardOpenOption.APPEND);

        try (Store store = open()) {
            assertEquals(List.of("a", "b"), ids(store.readAll("things")));
            store.create("things", item("d"));
        }
        try (Store store = open()) {
            assertEquals(List.of("a", "b", "d"), ids(store.readAll("things")));
        }
        assertEquals(3, lines("things").size());
    }

    @Test
    void skipsDamagedLastRecord() throws IOException {
        try (Store store = open()) {
            store.create("things", item("a"));
        }
        Files.writeString(directory.resolve("things.txt"), "P 00000000 AAAA\n", StandardOpenOption.APPEND);

        try (Store store = open()) {
            assertEquals(List.of("a"), ids(store.readAll("things")));
            store.create("things", item("b"));
        }
        try (Store store = open()) {
            assertEquals(List.of("a", "b"), ids(store.readAll("things")));
        }
    }

    @Test
    void refusesDamagedRecordBeforeTheEnd() throws IOException {
        try (Store store = open()) {
            store.create("things", item("a"));
        }
        Path log = directory.resolve("things.txt");
        String good = Files.readString(log);
        Files.writeString(log, "garbage\n" + good);

        assertThrows(UncheckedIOException.class, this::open);
        assertThrows(UncheckedIOException.class, this::open);
    }

    @Test
    void refusesSecondStoreOnSameDirectory() {
        try (Store store = open()) {
            store.create("things", item("a"));
            assertThrows(IllegalStateException.class, this::open);
        }
        try (Store store = open()) {
            assertEquals(1, store.count("things"));
        }
    }

    @Test
    void rejectsUnknownFsyncPolicy() {
        assertThrows(IllegalArgumentException.class, () -> Store.open(directory, "sometimes"));
        assertEquals(FsyncPolicy.BATCH, FsyncPolicy.parse(" Batch "));
    }

    @ParameterizedTest
    @EnumSource(FsyncPolicy.class)
    void persistsWithEveryFsyncPolicy(FsyncPolicy policy) throws Exception {
        try (Store store = Store.open(directory, policy.name().toLowerCase())) {
            for (int i = 0; i < 100; i++) {
                store.create("things", item("id" + i, "v", Integer.toString(i)));
            }
            assertEquals(100, lines("things").size());
            if (policy == FsyncPolicy.BATCH) {
                Thread.sleep(Store.BATCH_INTERVAL_MS * 5);
            }
        }
        try (Store store = Store.open(directory, policy)) {
            assertEquals(100, store.count("things"));
            assertEquals("99", store.read("things", "id99").orElseThrow().getFieldsOrThrow("v"));
        }
    }

    @Test
    void rejectsWritesAfterClose() {
        Store store = open();
        store.create("things", item("a"));
        store.close();

        assertEquals(Status.Code.UNAVAILABLE, codeOf(() -> store.create("things", item("b"))));
        assertEquals(Status.Code.UNAVAILABLE, codeOf(() -> store.create("fresh", item("b"))));
    }

    @Test
    void exactlyOneConditionalUpsertWinsPerExpectedState() throws Exception {
        int threads = 16;
        int rounds = 50;
        try (Store store = Store.open(directory, FsyncPolicy.NEVER)) {
            store.create("things", item("x", "version", "0"));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int round = 0; round < rounds; round++) {
                    String from = Integer.toString(round);
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<UpsertOutcome>> results = new ArrayList<>();
                    for (int t = 0; t < threads; t++) {
                        String writer = "w" + t;
                        results.add(pool.submit(() -> {
                            start.await();
                            return store.upsert("things",
                                    item("x", "version", Integer.toString(Integer.parseInt(from) + 1), "writer", writer),
                                    Map.of("version", from)).getOutcome();
                        }));
                    }
                    start.countDown();
                    int updated = 0;
                    for (Future<UpsertOutcome> result : results) {
                        UpsertOutcome outcome = result.get(10, TimeUnit.SECONDS);
                        if (outcome == UpsertOutcome.UPDATED) {
                            updated++;
                        } else {
                            assertEquals(UpsertOutcome.UNCHANGED, outcome);
                        }
                    }
                    assertEquals(1, updated, "round " + round);
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(Integer.toString(rounds), store.read("things", "x").orElseThrow().getFieldsOrThrow("version"));
        }
        try (Store store = open()) {
            assertEquals(Integer.toString(rounds), store.read("things", "x").orElseThrow().getFieldsOrThrow("version"));
        }
    }

    @Test
    void exactlyOneConcurrentCreateWins() throws Exception {
        int threads = 16;
        try (Store store = open()) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            runConcurrently(threads, t -> {
                try {
                    store.create("things", item("same", "writer", Integer.toString(t)));
                    created.incrementAndGet();
                } catch (StoreException e) {
                    assertEquals(Status.Code.ALREADY_EXISTS, e.code());
                    rejected.incrementAndGet();
                }
                return null;
            });
            assertEquals(1, created.get());
            assertEquals(threads - 1, rejected.get());
            assertEquals(1, lines("things").size());
        }
    }

    @Test
    void compareAndSetCounterLosesNoIncrements() throws Exception {
        int threads = 8;
        int increments = 200;
        try (Store store = Store.open(directory, FsyncPolicy.BATCH)) {
            store.create("counters", item("c", "n", "0"));
            runConcurrently(threads, t -> {
                for (int i = 0; i < increments; i++) {
                    while (true) {
                        String current = store.read("counters", "c").orElseThrow().getFieldsOrThrow("n");
                        Item next = item("c", "n", Integer.toString(Integer.parseInt(current) + 1));
                        try {
                            store.update("counters", next, Map.of("n", current));
                            break;
                        } catch (StoreException e) {
                            assertEquals(Status.Code.FAILED_PRECONDITION, e.code());
                        }
                    }
                }
                return null;
            });
            assertEquals(Integer.toString(threads * increments), store.read("counters", "c").orElseThrow().getFieldsOrThrow("n"));
        }
        try (Store store = open()) {
            assertEquals(Integer.toString(threads * increments), store.read("counters", "c").orElseThrow().getFieldsOrThrow("n"));
        }
    }

    @Test
    void concurrentWritersAcrossCollectionsAllReplay() throws Exception {
        int threads = 8;
        int perThread = 100;
        try (Store store = open()) {
            runConcurrently(threads, t -> {
                for (int i = 0; i < perThread; i++) {
                    store.create("c" + (t % 3), item(t + "-" + i, "v", Integer.toString(i)));
                }
                return null;
            });
        }
        try (Store store = open()) {
            long total = store.count("c0") + store.count("c1") + store.count("c2");
            assertEquals(threads * perThread, total);
            for (int t = 0; t < threads; t++) {
                String prefix = t + "-";
                List<String> mine = ids(store.readAll("c" + (t % 3))).stream()
                        .filter(id -> id.startsWith(prefix))
                        .toList();
                List<String> expected = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    expected.add(t + "-" + i);
                }
                assertEquals(expected, mine);
            }
        }
    }

    private interface Task {
        Void run(int thread) throws Exception;
    }

    private static void runConcurrently(int threads, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                Callable<Void> callable = () -> {
                    start.await();
                    return task.run(thread);
                };
                futures.add(pool.submit(callable));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
