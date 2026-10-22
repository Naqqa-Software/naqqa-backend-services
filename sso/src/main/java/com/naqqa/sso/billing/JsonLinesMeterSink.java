package com.naqqa.sso.billing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class JsonLinesMeterSink implements MeterSink {

    private final Path file;
    private final Object lock = new Object();

    public JsonLinesMeterSink(Path file) {
        this.file = file;
    }

    @Override
    public void accept(UsageEvent event) {
        byte[] line = (event.toJson() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
