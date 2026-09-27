package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.index.translog.Operation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class BufferingLiveOpsSource implements LiveOpsSource {

    private final Object lock = new Object();
    private final NavigableMap<Long, Operation> ops = new TreeMap<>();

    public void record(Operation op) {
        synchronized (lock) {
            ops.put(op.seqNo(), op);
        }
    }

    @Override
    public Iterator<Operation> opsSince(long fromSeqNo) {
        List<Operation> out = new ArrayList<>();
        synchronized (lock) {
            out.addAll(ops.tailMap(fromSeqNo, true).values());
        }
        return out.iterator();
    }
}
