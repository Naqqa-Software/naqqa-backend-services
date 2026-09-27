package com.naqqa.elasticsearch.search.bridge.join;

public interface ParentChildDocMapping {

    boolean isChild(int doc);

    int parentOf(int childDoc);
}
