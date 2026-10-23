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

public final class SeedLoader {
    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

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
        int created = 0;
        for (Item item : read(collection)) {
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
}
