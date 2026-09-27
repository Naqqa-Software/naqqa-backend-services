package com.naqqa.elasticsearch.indices.resize;

public final class IndexNotReadOnlyException extends RuntimeException {

    public IndexNotReadOnlyException(String index) {
        super("index [" + index + "] must be marked read-only (index.blocks.write) before it can be resized;"
            + " call setIndexReadOnly / add_block [write] first");
    }
}
