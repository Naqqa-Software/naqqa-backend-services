package com.naqqa.elasticsearch.snapshots.source;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface ShardSnapshotSource {

    List<String> listSegmentFiles();

    InputStream openFile(String name) throws IOException;

    long fileLength(String name);

    String fileChecksum(String name);
}
