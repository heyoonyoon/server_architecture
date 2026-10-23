package com.serverarchitecture.data;

import com.serverarchitecture.data.api.Item;
import com.serverarchitecture.data.api.UpsertOutcome;
import com.serverarchitecture.data.api.UpsertResponse;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class StoredCollection implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(StoredCollection.class);

    private final String name;
    private final FsyncPolicy fsync;
    private final LinkedHashMap<String, Item> items;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final FileChannel channel;
    private long size;
    private volatile boolean dirty;
    private boolean closed;

    private StoredCollection(String name, FsyncPolicy fsync, LinkedHashMap<String, Item> items, FileChannel channel, long size) {
        this.name = name;
        this.fsync = fsync;
        this.items = items;
        this.channel = channel;
        this.size = size;
    }

    static Path logPath(Path directory, String name) {
        return directory.resolve(name + ".log");
    }

    static StoredCollection open(Path directory, String name, FsyncPolicy fsync) throws IOException {
        Path path = logPath(directory, name);
        boolean created = Files.notExists(path);
        LinkedHashMap<String, Item> items = new LinkedHashMap<>();
        long records = 0;
        long valid = 0;
        if (!created) {
            byte[] bytes = Files.readAllBytes(path);
            int start = 0;
            int line = 1;
            while (start < bytes.length) {
                int end = indexOfNewline(bytes, start);
                if (end < 0) {
                    log.warn("줄바꿈 없이 끝난 마지막 기록 버림 {} 위치 {} 길이 {}", path, start, bytes.length - start);
                    break;
                }
                LogRecord record = LogRecord.decode(bytes, start, end);
                if (record == null) {
                    if (end + 1 == bytes.length) {
                        log.warn("손상된 마지막 기록 버림 {} 줄 {}", path, line);
                        break;
                    }
                    throw new IOException("손상된 기록 " + path + " 줄 " + line);
                }
                if (record.op() == LogRecord.PUT) {
                    items.put(record.id(), record.item());
                } else {
                    items.remove(record.id());
                }
                records++;
                line++;
                start = end + 1;
                valid = start;
            }
            if (valid < bytes.length) {
                try (FileChannel truncating = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    truncating.truncate(valid);
                    truncating.force(true);
                }
            }
            if (records > items.size()) {
                valid = compact(directory, name, path, items, records);
            }
        }
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (created && fsync != FsyncPolicy.NEVER) {
            Store.forceDirectory(directory);
        }
        if (created) {
            log.info("컬렉션 {} 새로 만듦", name);
        } else {
            log.info("컬렉션 {} 재생 항목 {}개 기록 {}개", name, items.size(), records);
        }
        return new StoredCollection(name, fsync, items, channel, valid);
    }

    private static long compact(Path directory, String name, Path path, Map<String, Item> items, long records) throws IOException {
        Path temporary = directory.resolve(name + ".log.tmp");
        long written = 0;
        try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (Item item : items.values()) {
                ByteBuffer buffer = ByteBuffer.wrap(LogRecord.put(item).encode());
                while (buffer.hasRemaining()) {
                    written += out.write(buffer);
                }
            }
            out.force(true);
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Store.forceDirectory(directory);
        log.info("컬렉션 {} 압축 기록 {}개 -> {}개", name, records, items.size());
        return written;
    }

    private static int indexOfNewline(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    Item create(Item item) {
        lock.writeLock().lock();
        try {
            if (items.containsKey(item.getId())) {
                throw new StoreException(Status.Code.ALREADY_EXISTS, "이미 있는 항목입니다: " + describe(item.getId()));
            }
            append(LogRecord.put(item));
            items.put(item.getId(), item);
            return item;
        } finally {
            lock.writeLock().unlock();
        }
    }

    Optional<Item> read(String id) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(items.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    List<Item> readAll() {
        lock.readLock().lock();
        try {
            return List.copyOf(items.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    List<Item> readWhere(String field, String value) {
        lock.readLock().lock();
        try {
            List<Item> matches = new ArrayList<>();
            for (Item item : items.values()) {
                if (item.getFieldsOrDefault(field, "").equals(value)) {
                    matches.add(item);
                }
            }
            return matches;
        } finally {
            lock.readLock().unlock();
        }
    }

    Item update(Item item, Map<String, String> expected) {
        lock.writeLock().lock();
        try {
            Item current = items.get(item.getId());
            if (current == null) {
                throw new StoreException(Status.Code.NOT_FOUND, "항목이 없습니다: " + describe(item.getId()));
            }
            if (!matches(current, expected)) {
                throw new StoreException(Status.Code.FAILED_PRECONDITION,
                        "항목이 그사이 바뀌어 수정하지 못했습니다: " + describe(item.getId()));
            }
            append(LogRecord.put(item));
            items.put(item.getId(), item);
            return item;
        } finally {
            lock.writeLock().unlock();
        }
    }

    UpsertResponse upsert(Item item, Map<String, String> expected) {
        lock.writeLock().lock();
        try {
            Item current = items.get(item.getId());
            if (current != null && !matches(current, expected)) {
                return UpsertResponse.newBuilder().setOutcome(UpsertOutcome.UNCHANGED).setItem(current).build();
            }
            append(LogRecord.put(item));
            items.put(item.getId(), item);
            return UpsertResponse.newBuilder()
                    .setOutcome(current == null ? UpsertOutcome.CREATED : UpsertOutcome.UPDATED)
                    .setItem(item)
                    .build();
        } finally {
            lock.writeLock().unlock();
        }
    }

    boolean delete(String id) {
        lock.writeLock().lock();
        try {
            if (!items.containsKey(id)) {
                return false;
            }
            append(LogRecord.delete(id));
            items.remove(id);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    long count() {
        lock.readLock().lock();
        try {
            return items.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    void flush() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            channel.force(false);
        } catch (IOException e) {
            dirty = true;
            log.error("컬렉션 {} fsync 실패", name, e);
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (fsync != FsyncPolicy.NEVER) {
                channel.force(false);
            }
            channel.close();
        } catch (IOException e) {
            log.error("컬렉션 {} 닫기 실패", name, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void append(LogRecord record) {
        if (closed) {
            throw new StoreException(Status.Code.UNAVAILABLE, "Data 서버가 종료 중입니다.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(record.encode());
        long position = size;
        try {
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
            if (fsync == FsyncPolicy.ALWAYS) {
                channel.force(false);
            } else if (fsync == FsyncPolicy.BATCH) {
                dirty = true;
            }
            size = position;
        } catch (IOException e) {
            rollback();
            throw new UncheckedIOException(e);
        }
    }

    private void rollback() {
        try {
            channel.truncate(size);
        } catch (IOException e) {
            log.error("컬렉션 {} 기록 되돌리기 실패", name, e);
        }
    }

    private static boolean matches(Item current, Map<String, String> expected) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!current.getFieldsOrDefault(entry.getKey(), "").equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private String describe(String id) {
        return name + "/" + id;
    }
}
