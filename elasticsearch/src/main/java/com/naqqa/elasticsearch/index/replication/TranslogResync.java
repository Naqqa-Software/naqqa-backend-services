package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.Translog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

final class TranslogResync {

    private TranslogResync() {
    }

    static List<Operation> opsFrom(IndexShard shard, long fromSeqNo) {
        List<Operation> ops = new ArrayList<>();
        try (Translog.Snapshot snapshot = shard.newTranslogSnapshot(Math.max(fromSeqNo, 0L))) {
            Operation op;
            while ((op = snapshot.next()) != null) {
                ops.add(op);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException("cannot access translog for resync: " + e.getMessage(), e);
        }
        return ops;
    }
}
