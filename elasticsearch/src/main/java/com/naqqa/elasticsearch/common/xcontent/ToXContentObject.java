package com.naqqa.elasticsearch.common.xcontent;

public interface ToXContentObject extends ToXContent {

    @Override
    default boolean isFragment() {
        return false;
    }
}
