package com.naqqa.elasticsearch.common.automaton;

public interface SortedTermSource {

    byte[] seekCeil(byte[] target);

    byte[] next();
}
