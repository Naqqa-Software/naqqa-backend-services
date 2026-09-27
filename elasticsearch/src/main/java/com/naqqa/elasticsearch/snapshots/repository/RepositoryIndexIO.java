package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.blobstore.BlobContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class RepositoryIndexIO {

    private static final String LATEST_POINTER = "index.latest";
    private static final String INDEX_PREFIX = "index-";

    private RepositoryIndexIO() {
    }

    public static RepositoryIndex readLatest(BlobContainer root) throws IOException {
        if (!root.blobExists(LATEST_POINTER)) {
            return RepositoryIndex.empty();
        }
        long generation = Long.parseLong(new String(root.readBlobFully(LATEST_POINTER), StandardCharsets.UTF_8).trim());
        byte[] bytes = root.readBlobFully(INDEX_PREFIX + generation);
        return RepositoryIndex.fromBytes(bytes);
    }

    public static void writeNext(BlobContainer root, RepositoryIndex newIndex) throws IOException {
        root.writeBlobAtomic(INDEX_PREFIX + newIndex.generation(), newIndex.toBytes());
        root.writeBlobAtomic(LATEST_POINTER, String.valueOf(newIndex.generation()).getBytes(StandardCharsets.UTF_8));
    }
}
