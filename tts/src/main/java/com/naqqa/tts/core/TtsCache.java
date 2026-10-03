package com.naqqa.tts.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class TtsCache {

    private static final Logger log = LoggerFactory.getLogger(TtsCache.class);

    private final Path dir;
    private final long maxBytes;
    private final AtomicLong size = new AtomicLong();

    public TtsCache(Path dir, long maxBytes) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                size.set(files.filter(Files::isRegularFile).mapToLong(TtsCache::sizeOf).sum());
            }
        } catch (IOException e) {
            log.warn("TTS cache directory {} is not usable: {}", dir, e.getMessage());
        }
    }

    public Optional<byte[]> get(String key) {
        Path file = file(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            return Optional.of(bytes);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public byte[] put(String key, Path source) throws IOException {
        Path target = file(key);
        Files.createDirectories(dir);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        byte[] bytes = Files.readAllBytes(target);
        if (size.addAndGet(bytes.length) > maxBytes) {
            evict();
        }
        return bytes;
    }

    public Path tempFile(String key) throws IOException {
        Files.createDirectories(dir);
        return dir.resolve(key + "." + System.nanoTime() + ".tmp");
    }

    public long size() {
        return size.get();
    }

    private synchronized void evict() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".wav")).forEach(files::add);
        } catch (IOException e) {
            return;
        }
        files.sort(Comparator.comparingLong(TtsCache::modified));
        long total = files.stream().mapToLong(TtsCache::sizeOf).sum();
        long target = (long) (maxBytes * 0.8);
        for (Path p : files) {
            if (total <= target) {
                break;
            }
            long s = sizeOf(p);
            try {
                Files.deleteIfExists(p);
                total -= s;
            } catch (IOException ignored) {
                continue;
            }
        }
        size.set(total);
    }

    private Path file(String key) {
        return dir.resolve(key + ".wav");
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
