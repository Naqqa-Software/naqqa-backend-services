package com.naqqa.elasticsearch.snapshots.blobstore;

import java.nio.file.Path;

public final class FsBlobStore implements BlobStore {

    private final Path root;

    public FsBlobStore(Path root) {
        this.root = root;
    }

    @Override
    public BlobContainer container(String path) {
        Path dir = path == null || path.isEmpty() ? root : root.resolve(path);
        return new FsBlobContainer(root, dir);
    }
}
