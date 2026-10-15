package com.serverarchitecture.redis;

import java.util.List;

public class Transaction {
    private final RedisConnection connection;
    private int queued;

    Transaction(RedisConnection connection) {
        this.connection = connection;
        send("MULTI");
    }

    public void zadd(String key, double score, String member) {
        send("ZADD", key, RedisConnection.formatScore(score), member);
    }

    public void zadd(String key, double score, String member, ZAddCondition condition) {
        send("ZADD", key, condition.name(), RedisConnection.formatScore(score), member);
    }

    @SuppressWarnings("unchecked")
    public List<Object> exec() {
        send("EXEC");
        List<Object> replies = connection.readReplies(queued);
        Object result = replies.get(replies.size() - 1);
        if (result instanceof RedisErrorException e) {
            throw e;
        }
        return (List<Object>) result;
    }

    private void send(String... command) {
        connection.send(List.of(command));
        queued++;
    }
}
