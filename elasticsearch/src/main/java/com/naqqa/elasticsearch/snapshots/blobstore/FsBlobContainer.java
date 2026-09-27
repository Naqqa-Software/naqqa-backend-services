package com.naqqa.elasticsearch.snapshots.blobstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class FsBlobContainer implements BlobContainer {

    private final Path root;
    private final Path dir;
    private final AtomicLong tempCounter = new AtomicLong();

    public FsBlobContainer(Path root, Path dir) {
        this.root = root;
        this.dir = dir;
    }

    @Override
    public String path() {
        return root.relativize(dir).toString().replace('\\', '/');
    }

    private void ensureDir() throws IOException {
        Files.createDirectories(dir);
    }

    @Override
    public boolean blobExists(String blobName) throws IOException {
        return Files.exists(dir.resolve(blobName));
    }

    @Override
    public InputStream readBlob(String blobName) throws IOException {
        return Files.newInputStream(dir.resolve(blobName), StandardOpenOption.READ);
    }

    @Override
    public byte[] readBlobFully(String blobName) throws IOException {
        return Files.readAllBytes(dir.resolve(blobName));
    }

    @Override
    public void writeBlob(String blobName, byte[] data, boolean failIfExists) throws IOException {
        ensureDir();
        Path target = dir.resolve(blobName);
        if (failIfExists && Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write(data);
        }
    }

    @Override
    public void writeBlobAtomic(String blobName, byte[] data) throws IOException {
        ensureDir();
        Path target = dir.resolve(blobName);
        Path temp = dir.resolve(blobName + ".tmp-" + tempCounter.incrementAndGet());
        try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write(data);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileSystemException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public Map<String, Long> listBlobs() throws IOException {
        Map<String, Long> result = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                if (Files.isRegularFile(p) && !p.getFileName().toString().contains(".tmp-")) {
                    result.put(p.getFileName().toString(), Files.size(p));
                }
            }
        }
        return result;
    }

    @Override
    public void deleteBlob(String blobName) throws IOException {
        Files.deleteIfExists(dir.resolve(blobName));
    }

    @Override
    public BlobContainer child(String name) {
        return new FsBlobContainer(root, dir.resolve(name));
    }
}
