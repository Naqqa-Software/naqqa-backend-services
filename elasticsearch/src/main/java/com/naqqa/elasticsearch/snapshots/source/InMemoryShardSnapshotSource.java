package com.naqqa.elasticsearch.snapshots.source;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryShardSnapshotSource implements ShardSnapshotSource {

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    public InMemoryShardSnapshotSource putFile(String name, byte[] content) {
        files.put(name, content);
        return this;
    }

    @Override
    public List<String> listSegmentFiles() {
        return List.copyOf(files.keySet());
    }

    @Override
    public InputStream openFile(String name) {
        byte[] content = files.get(name);
        if (content == null) {
            throw new IllegalArgumentException("No such file: " + name);
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public long fileLength(String name) {
        byte[] content = files.get(name);
        if (content == null) {
            throw new IllegalArgumentException("No such file: " + name);
        }
        return content.length;
    }

    @Override
    public String fileChecksum(String name) {
        byte[] content = files.get(name);
        if (content == null) {
            throw new IllegalArgumentException("No such file: " + name);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
