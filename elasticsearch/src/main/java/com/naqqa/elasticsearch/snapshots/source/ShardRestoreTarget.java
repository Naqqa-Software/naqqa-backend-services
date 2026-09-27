package com.naqqa.elasticsearch.snapshots.source;

import java.io.IOException;
import java.io.OutputStream;

public interface ShardRestoreTarget {

    OutputStream createFile(String name) throws IOException;
}
