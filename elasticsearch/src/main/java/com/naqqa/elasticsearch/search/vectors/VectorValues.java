package com.naqqa.elasticsearch.search.vectors;

public interface VectorValues {

    int size();

    int dimension();

    ElementType elementType();

    default int ordToDoc(int ord) {
        return ord;
    }
}
