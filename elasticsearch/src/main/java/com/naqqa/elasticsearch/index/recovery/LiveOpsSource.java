package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.index.translog.Operation;

import java.util.Iterator;

public interface LiveOpsSource {

    Iterator<Operation> opsSince(long fromSeqNo);
}
