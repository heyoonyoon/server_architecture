package com.serverarchitecture;

import com.serverarchitecture.data.api.Item;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SeedLoader {
    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_DELAY_MS = 1000;
    public static final String LOADTEST_PASSWORD = "loadtest0811";
    public static final int LOADTEST_FIRST_STUDENT_ID = 30000001;
    public static final int LOADTEST_STUDENT_COUNT = 3660;

    private SeedLoader() {
    }

    public static List<Item> read(String collection) {
        String path = "/seed/" + collection + ".tsv";
        InputStream input = SeedLoader.class.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException("시드 파일이 없습니다: " + path);
        }
        List<Item> items = new ArrayList<Item>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String[] header = reader.readLine().split("\t", -1);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split("\t", -1);
                Item.Builder item = Item.newBuilder().setId(values[0]);
                for (int i = 1; i < header.length; i++) {
                    item.putFields(header[i], i < values.length ? values[i] : "");
                }
                items.add(item.build());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return items;
    }

    public static void seedIfEmpty(DataClient data, String collection) {
        if (data.count(collection) > 0) {
            return;
        }
        createMissing(data, collection, read(collection));
    }

    public static List<Item> loadTestItems(String collection) {
        return switch (collection) {
            case DataCollections.USERS -> loadTestUsers();
            case DataCollections.STUDENTS -> loadTestStudents();
            default -> read("loadtest/" + collection);
        };
    }

    public static void seedWithRetry(DataClient data, String... collections) throws InterruptedException {
        for (String collection : collections) {
            withRetry(collection, () -> seedIfEmpty(data, collection));
        }
    }

    public static void seedLoadTestWithRetry(DataClient data, String... collections) throws InterruptedException {
        for (String collection : collections) {
            List<Item> items = loadTestItems(collection);
            withRetry(collection, () -> createMissing(data, collection, items));
        }
    }

    private static List<Item> loadTestUsers() {
        String passwordHash = PasswordHasher.hash(LOADTEST_PASSWORD);
        List<Item> users = new ArrayList<Item>();
        for (String id : loadTestStudentIds()) {
            users.add(DataClient.item(id, Map.of(
                    "name", "Load " + id,
                    "role", Role.STUDENT.name(),
                    "password_hash", passwordHash)));
        }
        return users;
    }

    private static List<Item> loadTestStudents() {
        List<Item> students = new ArrayList<Item>();
        for (String id : loadTestStudentIds()) {
            students.add(DataClient.item(id, Map.of(
                    "name", "Load " + id,
                    "department", "CS",
                    "max_credits", "18",
                    "completed_course_ids", "")));
        }
        return students;
    }

    private static List<String> loadTestStudentIds() {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < LOADTEST_STUDENT_COUNT; i++) {
            ids.add(String.valueOf(LOADTEST_FIRST_STUDENT_ID + i));
        }
        return ids;
    }

    private static void createMissing(DataClient data, String collection, List<Item> items) {
        int created = 0;
        for (Item item : items) {
            try {
                data.create(collection, item);
                created++;
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != Status.Code.ALREADY_EXISTS) {
                    throw e;
                }
            }
        }
        log.info("시드 적재 collection={} created={}", collection, created);
    }

    private static void withRetry(String collection, Runnable seed) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                seed.run();
                return;
            } catch (StatusRuntimeException e) {
                Status.Code code = e.getStatus().getCode();
                boolean retryable = code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("데이터 서버 대기 중 collection={} attempt={}/{}: {}", collection, attempt, MAX_ATTEMPTS,
                        e.getStatus().getCode());
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }
}
