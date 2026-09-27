package com.naqqa.elasticsearch.codec;

public interface ByteAutomaton {

    int initial();

    int step(int state, int label);

    boolean isAccept(int state);
}
