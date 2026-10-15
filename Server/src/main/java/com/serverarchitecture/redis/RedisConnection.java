package com.serverarchitecture.redis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RedisConnection implements AutoCloseable {
    private final Socket socket;
    private final RespReader reader;
    private final OutputStream out;
    private RedisPool pool;
    private boolean broken;
    private int pendingReplies;

    private RedisConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new RespReader(new BufferedInputStream(socket.getInputStream()));
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    public static RedisConnection connect(String host, int port, int timeoutMillis) {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(timeoutMillis);
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return new RedisConnection(socket);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw new RedisConnectionException("Redis " + host + ":" + port + " 연결 실패: " + e.getMessage(), e);
        }
    }

    public Object call(String... command) {
        return call(List.of(command));
    }

    public Object call(List<String> command) {
        send(command);
        Object reply = readReply();
        if (reply instanceof RedisErrorException e) {
            throw e;
        }
        return reply;
    }

    public String ping() {
        return (String) call("PING");
    }

    public String get(String key) {
        return (String) call("GET", key);
    }

    public String set(String key, String value) {
        return (String) call("SET", key, value);
    }

    public boolean exists(String key) {
        return (Long) call("EXISTS", key) > 0;
    }

    public Double zscore(String key, String member) {
        String score = (String) call("ZSCORE", key, member);
        return score == null ? null : parseScore(score);
    }

    public long zadd(String key, double score, String member) {
        return (Long) call("ZADD", key, formatScore(score), member);
    }

    public long zadd(String key, double score, String member, ZAddCondition condition) {
        return (Long) call("ZADD", key, condition.name(), formatScore(score), member);
    }

    public Long zrank(String key, String member) {
        return (Long) call("ZRANK", key, member);
    }

    public long zcard(String key) {
        return (Long) call("ZCARD", key);
    }

    public Set<String> smembers(String key) {
        Set<String> members = new LinkedHashSet<>();
        for (Object member : (List<?>) call("SMEMBERS", key)) {
            members.add((String) member);
        }
        return members;
    }

    public Map<String, String> hgetAll(String key) {
        List<?> reply = (List<?>) call("HGETALL", key);
        Map<String, String> hash = new LinkedHashMap<>();
        for (int i = 0; i + 1 < reply.size(); i += 2) {
            hash.put((String) reply.get(i), (String) reply.get(i + 1));
        }
        return hash;
    }

    public Object eval(String script, List<String> keys, List<String> args) {
        return call(scriptCommand("EVAL", script, keys, args));
    }

    public Object evalsha(String sha, List<String> keys, List<String> args) {
        return call(scriptCommand("EVALSHA", sha, keys, args));
    }

    public Pipeline pipelined() {
        return new Pipeline(this);
    }

    public Transaction multi() {
        return new Transaction(this);
    }

    public boolean isBroken() {
        return broken;
    }

    @Override
    public void close() {
        RedisPool owner = pool;
        if (owner == null) {
            disconnect();
            return;
        }
        pool = null;
        owner.giveBack(this);
    }

    void lease(RedisPool owner) {
        this.pool = owner;
    }

    boolean hasPendingReplies() {
        return pendingReplies > 0;
    }

    void disconnect() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    void send(List<String> command) {
        try {
            RespEncoder.write(out, command);
            pendingReplies++;
        } catch (IOException e) {
            throw failed(e);
        }
    }

    List<Object> readReplies(int count) {
        List<Object> replies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            replies.add(readReply());
        }
        return replies;
    }

    private Object readReply() {
        try {
            out.flush();
            Object reply = reader.read();
            pendingReplies--;
            return reply;
        } catch (IOException e) {
            throw failed(e);
        }
    }

    private RedisConnectionException failed(IOException e) {
        broken = true;
        return new RedisConnectionException("Redis 통신 실패: " + e.getMessage(), e);
    }

    private static List<String> scriptCommand(String name, String script, List<String> keys, List<String> args) {
        List<String> command = new ArrayList<>(3 + keys.size() + args.size());
        command.add(name);
        command.add(script);
        command.add(String.valueOf(keys.size()));
        command.addAll(keys);
        command.addAll(args);
        return command;
    }

    static String formatScore(double score) {
        if (score == Math.rint(score) && Math.abs(score) < 0x1p53) {
            return Long.toString((long) score);
        }
        if (Double.isInfinite(score)) {
            return score > 0 ? "+inf" : "-inf";
        }
        return Double.toString(score);
    }

    static double parseScore(String score) {
        return switch (score) {
            case "inf", "+inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(score);
        };
    }
}
