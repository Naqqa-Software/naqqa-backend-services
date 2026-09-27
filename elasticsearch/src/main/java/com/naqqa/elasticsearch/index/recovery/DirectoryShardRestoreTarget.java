package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.snapshots.source.ShardRestoreTarget;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.io.OutputStream;

final class DirectoryShardRestoreTarget implements ShardRestoreTarget {

    private final Directory directory;
    private final RecoveryThrottler throttler;

    DirectoryShardRestoreTarget(Directory directory, RecoveryThrottler throttler) {
        this.directory = directory;
        this.throttler = throttler;
    }

    @Override
    public OutputStream createFile(String name) throws IOException {
        if (directory.fileExists(name)) {
            directory.deleteFile(name);
        }
        IndexOutput out = directory.createOutput(name, IOContext.DEFAULT);
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                out.writeByte((byte) b);
                throttler.throttle(1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.writeBytes(b, off, len);
                throttler.throttle(len);
            }

            @Override
            public void close() throws IOException {
                out.close();
            }
        };
    }
}
