package com.serverarchitecture.redis;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RespTest {
    private static RespReader reader(String data) {
        return new RespReader(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static RespReader trickle(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return new RespReader(new InputStream() {
            private int position;

            @Override
            public int read() {
                return position < bytes.length ? bytes[position++] & 0xff : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (length == 0) {
                    return 0;
                }
                int b = read();
                if (b == -1) {
                    return -1;
                }
                buffer[offset] = (byte) b;
                return 1;
            }
        });
    }

    @Test
    void encodesCommandAsArrayOfBulkStrings() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        RespEncoder.write(out, List.of("SET", "학생", "", "a\r\nb"));

        assertEquals("*4\r\n$3\r\nSET\r\n$6\r\n학생\r\n$0\r\n\r\n$4\r\na\r\nb\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void readsSimpleString() throws IOException {
        assertEquals("OK", reader("+OK\r\n").read());
    }

    @Test
    void readsErrorAsValue() throws IOException {
        Object reply = reader("-ERR wrong number of arguments\r\n").read();

        RedisErrorException error = assertInstanceOf(RedisErrorException.class, reply);
        assertEquals("ERR wrong number of arguments", error.getMessage());
    }

    @Test
    void readsNoScriptAsDedicatedError() throws IOException {
        Object reply = reader("-NOSCRIPT No matching script. Please use EVAL.\r\n").read();

        assertInstanceOf(RedisNoScriptException.class, reply);
    }

    @Test
    void readsIntegers() throws IOException {
        RespReader reader = reader(":0\r\n:-3\r\n:1760000000000\r\n");

        assertEquals(0L, reader.read());
        assertEquals(-3L, reader.read());
        assertEquals(1760000000000L, reader.read());
    }

    @Test
    void readsBulkStrings() throws IOException {
        RespReader reader = reader("$5\r\nhello\r\n$0\r\n\r\n$-1\r\n$4\r\na\r\nb\r\n$6\r\n학생\r\n");

        assertEquals("hello", reader.read());
        assertEquals("", reader.read());
        assertNull(reader.read());
        assertEquals("a\r\nb", reader.read());
        assertEquals("학생", reader.read());
    }

    @Test
    void readsNestedAndNullArrays() throws IOException {
        RespReader reader = reader("*3\r\n:1\r\n*2\r\n$1\r\na\r\n$-1\r\n*-1\r\n*0\r\n*-1\r\n");

        assertEquals(Arrays.asList(1L, Arrays.asList("a", null), null), reader.read());
        assertEquals(List.of(), reader.read());
        assertNull(reader.read());
    }

    @Test
    void readsArrayContainingError() throws IOException {
        List<?> reply = (List<?>) reader("*2\r\n:1\r\n-WRONGTYPE bad\r\n").read();

        assertEquals(1L, reply.get(0));
        assertInstanceOf(RedisErrorException.class, reply.get(1));
    }

    @Test
    void readsRepliesArrivingOneByteAtATime() throws IOException {
        RespReader reader = trickle("*2\r\n:7\r\n$11\r\nhello world\r\n+PONG\r\n$-1\r\n");

        assertEquals(List.of(7L, "hello world"), reader.read());
        assertEquals("PONG", reader.read());
        assertNull(reader.read());
    }

    @Test
    void truncatedReplyIsEndOfStream() {
        assertThrows(EOFException.class, () -> reader("").read());
        assertThrows(EOFException.class, () -> reader("+OK").read());
        assertThrows(EOFException.class, () -> reader("$10\r\nshort\r\n").read());
        assertThrows(EOFException.class, () -> reader("*2\r\n:1\r\n").read());
    }

    @Test
    void malformedReplyIsProtocolError() {
        assertThrows(ProtocolException.class, () -> reader("?what\r\n").read());
        assertThrows(ProtocolException.class, () -> reader(":abc\r\n").read());
        assertThrows(ProtocolException.class, () -> reader("$3\r\nabcde\r\n").read());
    }

    @Test
    void scoresRoundTrip() {
        assertEquals("1760000000000", RedisConnection.formatScore(1760000000000.0));
        assertEquals("1.5", RedisConnection.formatScore(1.5));
        assertEquals("+inf", RedisConnection.formatScore(Double.POSITIVE_INFINITY));
        assertEquals(1760000000000.0, RedisConnection.parseScore("1760000000000"));
        assertEquals(Double.NEGATIVE_INFINITY, RedisConnection.parseScore("-inf"));
    }
}
