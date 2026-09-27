package com.naqqa.elasticsearch.indices;

import com.naqqa.elasticsearch.cluster.state.IndexMetadata;

public final class IndexStateService {

    private IndexStateService() {
    }

    public static IndexMetadata close(IndexMetadata metadata) {
        if (metadata.getState() == IndexMetadata.State.CLOSE) {
            return metadata;
        }
        return metadata.builder().state(IndexMetadata.State.CLOSE).version(metadata.getVersion() + 1).build();
    }

    public static IndexMetadata open(IndexMetadata metadata) {
        if (metadata.getState() == IndexMetadata.State.OPEN) {
            return metadata;
        }
        return metadata.builder().state(IndexMetadata.State.OPEN).version(metadata.getVersion() + 1).build();
    }

    public static boolean isClosed(IndexMetadata metadata) {
        return metadata.getState() == IndexMetadata.State.CLOSE;
    }

    public static void checkNotClosed(IndexMetadata metadata) {
        if (isClosed(metadata)) {
            throw new ClosedIndexException(metadata.getIndex());
        }
    }

    public static void checkOpenForReadOrWrite(IndexMetadata metadata) {
        checkNotClosed(metadata);
    }
}
