package com.serverarchitecture.data;

import com.serverarchitecture.data.api.Item;
import com.serverarchitecture.data.api.UpsertResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Store implements AutoCloseable {
    public static Store open(Path directory, String fsync) {
        throw new UnsupportedOperationException("Store.open");
    }

    public Item create(String collection, Item item) {
        throw new UnsupportedOperationException("Store.create");
    }

    public Optional<Item> read(String collection, String id) {
        throw new UnsupportedOperationException("Store.read");
    }

    public List<Item> readAll(String collection) {
        throw new UnsupportedOperationException("Store.readAll");
    }

    public List<Item> readWhere(String collection, String field, String value) {
        throw new UnsupportedOperationException("Store.readWhere");
    }

    public Item update(String collection, Item item, Map<String, String> expected) {
        throw new UnsupportedOperationException("Store.update");
    }

    public UpsertResponse upsert(String collection, Item item, Map<String, String> expected) {
        throw new UnsupportedOperationException("Store.upsert");
    }

    public boolean delete(String collection, String id) {
        throw new UnsupportedOperationException("Store.delete");
    }

    public long count(String collection) {
        throw new UnsupportedOperationException("Store.count");
    }

    @Override
    public void close() {
    }
}
