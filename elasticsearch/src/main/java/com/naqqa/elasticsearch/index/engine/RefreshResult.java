package com.naqqa.elasticsearch.index.engine;

public record RefreshResult(boolean refreshed, int segmentCount) {
}
