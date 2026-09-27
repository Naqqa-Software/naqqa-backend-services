package com.naqqa.elasticsearch.common.bytes;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;

public interface BytesReference {

    int length();

    byte get(int index);

    BytesReference slice(int from, int length);

    byte[] toBytesArray();

    BytesRef toBytesRef();

    StreamInput streamInput();

    String utf8ToString();

    static BytesReference of(byte[] bytes) {
        return new BytesArray(bytes);
    }
}
