package com.serverarchitecture.logging;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class RollingLogFile implements AutoCloseable {
    private final Path directory;
    private final String baseName;
    private final int maxHistoryDays;
    private final Path activeFile;
    private Writer writer;
    private LocalDate period;
    private boolean failed;

    public RollingLogFile(Path directory, String baseName, int maxHistoryDays) {
        this.directory = directory;
        this.baseName = baseName;
        this.maxHistoryDays = maxHistoryDays;
        this.activeFile = directory.resolve(baseName + ".log");
    }

    public Path activeFile() {
        return activeFile;
    }

    public Path archiveFile(LocalDate date) {
        return directory.resolve(baseName + "." + date + ".log");
    }

    public void write(Instant time, String text) {
        if (failed) {
            return;
        }
        try {
            LocalDate date = LocalDate.ofInstant(time, LogFormat.ZONE);
            if (period == null) {
                period = Files.exists(activeFile)
                        ? LocalDate.ofInstant(Files.getLastModifiedTime(activeFile).toInstant(), LogFormat.ZONE)
                        : date;
            }
            if (date.isAfter(period)) {
                rollover(date);
            }
            if (writer == null) {
                Files.createDirectories(directory);
                writer = Files.newBufferedWriter(activeFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            writer.write(text);
        } catch (IOException e) {
            fail(e);
        }
    }

    public void flush() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            fail(e);
        }
    }

    @Override
    public void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            fail(e);
        }
        writer = null;
    }

    private void rollover(LocalDate date) throws IOException {
        close();
        if (Files.exists(activeFile)) {
            Files.move(activeFile, archiveFile(period), StandardCopyOption.REPLACE_EXISTING);
        }
        period = date;
        deleteExpiredArchives(date);
    }

    private void deleteExpiredArchives(LocalDate today) throws IOException {
        LocalDate oldestKept = today.minusDays(maxHistoryDays);
        String prefix = baseName + ".";
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, prefix + "????-??-??.log")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                try {
                    LocalDate date = LocalDate.parse(name.substring(prefix.length(), name.length() - ".log".length()));
                    if (date.isBefore(oldestKept)) {
                        Files.deleteIfExists(file);
                    }
                } catch (DateTimeParseException ignored) {
                }
            }
        }
    }

    private void fail(IOException e) {
        failed = true;
        writer = null;
        System.err.println("Log file " + activeFile + " disabled: " + e);
    }
}
