package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.index.engine.Engine;
import com.naqqa.elasticsearch.index.engine.InternalEngine;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.Translog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

final class TranslogResync {

    private TranslogResync() {
    }

    static List<Operation> opsFrom(IndexShard shard, long fromSeqNo) {
        Engine engine = shard.engine();
        if (!(engine instanceof InternalEngine)) {
            throw new IllegalStateException("cannot access translog for resync: unsupported engine type ["
                + engine.getClass().getName() + "]");
        }
        Translog translog = extractTranslog((InternalEngine) engine);
        List<Operation> ops = new ArrayList<>();
        try (Translog.Snapshot snapshot = translog.newSnapshot(Math.max(fromSeqNo, 0L))) {
            Operation op;
            while ((op = snapshot.next()) != null) {
                ops.add(op);
            }
        }
        return ops;
    }

    private static Translog extractTranslog(InternalEngine engine) {
        try {
            Field field = InternalEngine.class.getDeclaredField("translog");
            field.setAccessible(true);
            return (Translog) field.get(engine);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to access engine translog for primary/replica resync; "
                + "Engine does not expose a public newSnapshot(fromSeqNo) API", e);
        }
    }
}
