package com.serverarchitecture.data;

import com.google.protobuf.InvalidProtocolBufferException;
import com.serverarchitecture.data.api.Item;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

record LogRecord(char op, Item item, String id) {
    static final char PUT = 'P';
    static final char DELETE = 'D';

    static LogRecord put(Item item) {
        return new LogRecord(PUT, item, item.getId());
    }

    static LogRecord delete(String id) {
        return new LogRecord(DELETE, null, id);
    }

    byte[] encode() {
        byte[] payload = op == PUT ? item.toByteArray() : id.getBytes(StandardCharsets.UTF_8);
        String line = op + " " + String.format("%08x", checksum(op, payload)) + " "
                + Base64.getEncoder().encodeToString(payload) + "\n";
        return line.getBytes(StandardCharsets.US_ASCII);
    }

    static LogRecord decode(byte[] bytes, int start, int end) {
        String line = new String(bytes, start, end - start, StandardCharsets.US_ASCII);
        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || parts[0].length() != 1 || parts[1].length() != 8) {
            return null;
        }
        char op = parts[0].charAt(0);
        if (op != PUT && op != DELETE) {
            return null;
        }
        try {
            long expected = Long.parseLong(parts[1], 16);
            byte[] payload = Base64.getDecoder().decode(parts[2]);
            if (checksum(op, payload) != expected) {
                return null;
            }
            if (op == PUT) {
                Item item = Item.parseFrom(payload);
                return item.getId().isEmpty() ? null : put(item);
            }
            String id = new String(payload, StandardCharsets.UTF_8);
            return id.isEmpty() ? null : delete(id);
        } catch (IllegalArgumentException | InvalidProtocolBufferException e) {
            return null;
        }
    }

    private static long checksum(char op, byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(op);
        crc.update(payload);
        return crc.getValue();
    }
}
