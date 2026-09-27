package com.naqqa.elasticsearch.snapshots.blobstore;

public interface BlobStore {

    BlobContainer container(String path);
}
