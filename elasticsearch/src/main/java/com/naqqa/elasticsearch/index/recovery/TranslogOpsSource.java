package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.Translog;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TranslogOpsSource implements LiveOpsSource {

    private final Path translogPath;
    private final TranslogConfig config;

    public TranslogOpsSource(Path translogPath, TranslogConfig config) {
        this.translogPath = translogPath;
        this.config = config;
    }

    @Override
    public Iterator<Operation> opsSince(long fromSeqNo) {
        List<Operation> ops = new ArrayList<>();
        try (Translog translog = Translog.open(translogPath, config)) {
            try (Translog.Snapshot snapshot = translog.newSnapshot(fromSeqNo)) {
                Operation op;
                while ((op = snapshot.next()) != null) {
                    ops.add(op);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ops.iterator();
    }
}
