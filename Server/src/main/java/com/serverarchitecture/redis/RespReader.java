package com.serverarchitecture.redis;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RespReader {
    private final InputStream in;

    public RespReader(InputStream in) {
        this.in = in;
    }

    public Object read() throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException("Redis 연결이 끊어졌습니다.");
        }
        String line = readLine();
        return switch (type) {
            case '+' -> line;
            case '-' -> RedisErrorException.of(line);
            case ':' -> parseLong(line);
            case '$' -> readBulk(parseLength(line));
            case '*' -> readArray(parseLength(line));
            default -> throw new ProtocolException("알 수 없는 RESP 응답 유형: " + (char) type);
        };
    }

    private String readBulk(int length) throws IOException {
        if (length < 0) {
            return null;
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw new EOFException("Redis 응답이 중간에 끊어졌습니다.");
        }
        if (in.read() != '\r' || in.read() != '\n') {
            throw new ProtocolException("bulk string 끝에 CRLF가 없습니다.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private List<Object> readArray(int length) throws IOException {
        if (length < 0) {
            return null;
        }
        List<Object> elements = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            elements.add(read());
        }
        return elements;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("Redis 응답이 중간에 끊어졌습니다.");
            }
            if (b == '\r') {
                if (in.read() != '\n') {
                    throw new ProtocolException("CR 뒤에 LF가 없습니다.");
                }
                return line.toString(StandardCharsets.UTF_8);
            }
            line.write(b);
        }
    }

    private static int parseLength(String line) throws ProtocolException {
        return Math.toIntExact(parseLong(line));
    }

    private static long parseLong(String line) throws ProtocolException {
        try {
            return Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new ProtocolException("숫자가 아닌 RESP 값: " + line);
        }
    }
}
