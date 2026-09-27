package com.naqqa.elasticsearch.analysis;

public interface CharFilterFactory {

    String name();

    CharFilter create();

    default boolean isNormalizing() {
        return false;
    }
}
