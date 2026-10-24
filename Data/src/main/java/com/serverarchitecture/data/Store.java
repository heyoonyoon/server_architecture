package com.serverarchitecture.data;

import com.serverarchitecture.data.api.Item;
import com.serverarchitecture.data.api.UpsertResponse;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class Store implements AutoCloseable {
    static final long BATCH_INTERVAL_MS = 10;

    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private static final Pattern COLLECTION_NAME = Pattern.compile("[a-z0-9_]+");

    private final Path directory;
    private final FsyncPolicy fsync;
    private final FileChannel lockChannel;
    private final Map<String, StoredCollection> collections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher;
    private volatile boolean closed;

    private Store(Path directory, FsyncPolicy fsync, FileChannel lockChannel) {
        this.directory = directory;
        this.fsync = fsync;
        this.lockChannel = lockChannel;
        this.flusher = fsync == FsyncPolicy.BATCH ? Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "data-store-flusher");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    public static Store open(Path directory, String fsync) {
        return open(directory, FsyncPolicy.parse(fsync));
    }

    public static Store open(Path directory, FsyncPolicy fsync) {
        FileChannel lockChannel = null;
        Store store = null;
        try {
            Files.createDirectories(directory);
            lockChannel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (!tryLock(lockChannel)) {
                throw new IllegalStateException("다른 Data 서버가 이미 사용 중인 디렉터리입니다: " + directory.toAbsolutePath());
            }
            store = new Store(directory, fsync, lockChannel);
            store.load();
            store.startFlusher();
            return store;
        } catch (IOException e) {
            release(store, lockChannel);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            release(store, lockChannel);
            throw e;
        }
    }

    public Item create(String collection, Item item) {
        requireId(item.getId());
        return writable(collection).create(item);
    }

    public Optional<Item> read(String collection, String id) {
        StoredCollection stored = existing(collection);
        return stored == null ? Optional.empty() : stored.read(id);
    }

    public List<Item> readAll(String collection) {
        StoredCollection stored = existing(collection);
        return stored == null ? List.of() : stored.readAll();
    }

    public List<Item> readWhere(String collection, String field, String value) {
        if (field == null || field.isEmpty()) {
            throw new StoreException(Status.Code.INVALID_ARGUMENT, "필드 이름이 비어 있습니다.");
        }
        StoredCollection stored = existing(collection);
        return stored == null ? List.of() : stored.readWhere(field, value == null ? "" : value);
    }

    public Item update(String collection, Item item, Map<String, String> expected) {
        requireId(item.getId());
        StoredCollection stored = existing(collection);
        if (stored == null) {
            throw new StoreException(Status.Code.NOT_FOUND, "항목이 없습니다: " + collection + "/" + item.getId());
        }
        return stored.update(item, expected);
    }

    public UpsertResponse upsert(String collection, Item item, Map<String, String> expected) {
        requireId(item.getId());
        return writable(collection).upsert(item, expected);
    }

    public boolean delete(String collection, String id) {
        StoredCollection stored = existing(collection);
        return stored != null && stored.delete(id);
    }

    public long count(String collection) {
        StoredCollection stored = existing(collection);
        return stored == null ? 0 : stored.count();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (flusher != null) {
            flusher.shutdown();
            try {
                flusher.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        release(this, lockChannel);
        log.info("Data 저장소 닫힘 {}", directory.toAbsolutePath());
    }

    static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            log.debug("디렉터리 동기화 건너뜀 {}: {}", directory, e.toString());
        }
    }

    private void load() throws IOException {
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(directory, "*.txt.tmp")) {
            for (Path path : stale) {
                Files.delete(path);
            }
        }
        try (DirectoryStream<Path> logs = Files.newDirectoryStream(directory, "*.txt")) {
            for (Path path : logs) {
                String file = path.getFileName().toString();
                String name = file.substring(0, file.length() - ".txt".length());
                if (!COLLECTION_NAME.matcher(name).matches()) {
                    log.warn("이름이 올바르지 않은 로그 파일 건너뜀 {}", path);
                    continue;
                }
                collections.put(name, StoredCollection.open(directory, name, fsync));
            }
        }
        log.info("Data 저장소 열림 {} 컬렉션 {}개 fsync {}", directory.toAbsolutePath(), collections.size(),
                fsync.name().toLowerCase());
    }

    private void startFlusher() {
        if (flusher != null) {
            flusher.scheduleWithFixedDelay(() -> collections.values().forEach(StoredCollection::flush),
                    BATCH_INTERVAL_MS, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private StoredCollection existing(String collection) {
        requireName(collection);
        return collections.get(collection);
    }

    private StoredCollection writable(String collection) {
        requireName(collection);
        return collections.computeIfAbsent(collection, name -> {
            if (closed) {
                throw new StoreException(Status.Code.UNAVAILABLE, "Data 서버가 종료 중입니다.");
            }
            try {
                return StoredCollection.open(directory, name, fsync);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void requireName(String collection) {
        if (collection == null || !COLLECTION_NAME.matcher(collection).matches()) {
            throw new StoreException(Status.Code.INVALID_ARGUMENT,
                    "컬렉션 이름은 영문 소문자, 숫자, 밑줄만 쓸 수 있습니다: " + collection);
        }
    }

    private static void requireId(String id) {
        if (id.isEmpty()) {
            throw new StoreException(Status.Code.INVALID_ARGUMENT, "항목 id가 비어 있습니다.");
        }
    }

    private static boolean tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock() != null;
        } catch (OverlappingFileLockException e) {
            return false;
        }
    }

    private static void release(Store store, FileChannel lockChannel) {
        if (store != null) {
            if (store.flusher != null) {
                store.flusher.shutdownNow();
            }
            store.collections.values().forEach(StoredCollection::close);
        }
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException e) {
                log.warn("잠금 파일 닫기 실패: {}", e.toString());
            }
        }
    }
}
