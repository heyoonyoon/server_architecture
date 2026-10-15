package com.serverarchitecture.redis;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RespEncoder {
    private static final byte[] CRLF = {'\r', '\n'};

    private RespEncoder() {
    }

    public static void write(OutputStream out, List<String> command) throws IOException {
        writeHeader(out, '*', command.size());
        for (String argument : command) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            writeHeader(out, '$', bytes.length);
            out.write(bytes);
            out.write(CRLF);
        }
    }

    private static void writeHeader(OutputStream out, char type, int length) throws IOException {
        out.write(type);
        out.write(Integer.toString(length).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }
}
