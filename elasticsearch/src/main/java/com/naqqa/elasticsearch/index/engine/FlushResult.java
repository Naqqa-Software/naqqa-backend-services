package com.naqqa.elasticsearch.index.engine;

public record FlushResult(boolean flushed, long generation) {
}
