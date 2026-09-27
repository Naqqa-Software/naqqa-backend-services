package com.naqqa.elasticsearch.store;

import java.io.IOException;

public interface RandomAccessInput {

    long length();

    byte readByte(long pos) throws IOException;

    void readBytes(long pos, byte[] bytes, int offset, int length) throws IOException;

    short readShort(long pos) throws IOException;

    int readInt(long pos) throws IOException;

    long readLong(long pos) throws IOException;
}
