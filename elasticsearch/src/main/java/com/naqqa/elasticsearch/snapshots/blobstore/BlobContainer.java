package com.naqqa.elasticsearch.snapshots.blobstore;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public interface BlobContainer {

    String path();

    boolean blobExists(String blobName) throws IOException;

    InputStream readBlob(String blobName) throws IOException;

    byte[] readBlobFully(String blobName) throws IOException;

    void writeBlob(String blobName, byte[] data, boolean failIfExists) throws IOException;

    void writeBlobAtomic(String blobName, byte[] data) throws IOException;

    Map<String, Long> listBlobs() throws IOException;

    void deleteBlob(String blobName) throws IOException;

    BlobContainer child(String name);
}
