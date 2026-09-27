package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.blobstore.BlobContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.locks.ReentrantLock;

public final class ContentAddressedStore {

    private final ReentrantLock lock = new ReentrantLock();

    public record StoreResult(String blobName, long length, boolean alreadyExisted) {
    }

    public StoreResult store(BlobContainer dataContainer, InputStream content) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = content.read(buf)) != -1) {
            digest.update(buf, 0, n);
            buffer.write(buf, 0, n);
        }
        byte[] data = buffer.toByteArray();
        String hash = toHex(digest.digest());
        lock.lock();
        try {
            boolean existed = dataContainer.blobExists(hash);
            if (!existed) {
                dataContainer.writeBlob(hash, data, false);
                writeRefCount(dataContainer, hash, 1);
            } else {
                incrementRefCount(dataContainer, hash, 1);
            }
            return new StoreResult(hash, data.length, existed);
        } finally {
            lock.unlock();
        }
    }

    public void addReference(BlobContainer dataContainer, String blobName) throws IOException {
        lock.lock();
        try {
            incrementRefCount(dataContainer, blobName, 1);
        } finally {
            lock.unlock();
        }
    }

    public boolean release(BlobContainer dataContainer, String blobName) throws IOException {
        lock.lock();
        try {
            int count = readRefCount(dataContainer, blobName) - 1;
            if (count <= 0) {
                dataContainer.deleteBlob(blobName);
                dataContainer.deleteBlob(refCountName(blobName));
                return true;
            }
            writeRefCount(dataContainer, blobName, count);
            return false;
        } finally {
            lock.unlock();
        }
    }

    public int referenceCount(BlobContainer dataContainer, String blobName) throws IOException {
        lock.lock();
        try {
            return readRefCount(dataContainer, blobName);
        } finally {
            lock.unlock();
        }
    }

    private static String refCountName(String blobName) {
        return blobName + ".refcount";
    }

    private static int readRefCount(BlobContainer c, String blobName) throws IOException {
        if (!c.blobExists(refCountName(blobName))) {
            return 0;
        }
        byte[] bytes = c.readBlobFully(refCountName(blobName));
        return Integer.parseInt(new String(bytes, StandardCharsets.UTF_8).trim());
    }

    private static void writeRefCount(BlobContainer c, String blobName, int count) throws IOException {
        c.writeBlobAtomic(refCountName(blobName), String.valueOf(count).getBytes(StandardCharsets.UTF_8));
    }

    private static void incrementRefCount(BlobContainer c, String blobName, int delta) throws IOException {
        int current = readRefCount(c, blobName);
        writeRefCount(c, blobName, current + delta);
    }

    private static String toHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
