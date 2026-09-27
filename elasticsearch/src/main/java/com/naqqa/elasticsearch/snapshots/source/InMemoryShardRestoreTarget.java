package com.naqqa.elasticsearch.snapshots.source;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryShardRestoreTarget implements ShardRestoreTarget {

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    @Override
    public OutputStream createFile(String name) {
        return new ByteArrayOutputStream() {
            @Override
            public void close() {
                files.put(name, toByteArray());
            }
        };
    }

    public Map<String, byte[]> files() {
        return files;
    }
}
