package com.serverarchitecture.redis;

import java.util.List;

public class Pipeline {
    private final RedisConnection connection;
    private int queued;

    Pipeline(RedisConnection connection) {
        this.connection = connection;
    }

    public void hsetnx(String key, String field, String value) {
        send("HSETNX", key, field, value);
    }

    public void sadd(String key, String member) {
        send("SADD", key, member);
    }

    public List<Object> sync() {
        int count = queued;
        queued = 0;
        return connection.readReplies(count);
    }

    private void send(String... command) {
        connection.send(List.of(command));
        queued++;
    }
}
